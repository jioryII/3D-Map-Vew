package dev.maqueta.core;

import dev.maqueta.core.camera.HeightfieldRaycast;
import dev.maqueta.core.camera.OrbitCamera;
import dev.maqueta.core.cave.CaveMesher;
import dev.maqueta.core.cave.VoxelVolume;
import dev.maqueta.core.hud.HudAnchor;
import dev.maqueta.core.io.RegionStorage;
import dev.maqueta.core.light.HeightWindow;
import dev.maqueta.core.light.LightBaker;
import dev.maqueta.core.light.TimeGrading;
import dev.maqueta.core.marker.Trail;
import dev.maqueta.core.marker.Waypoint;
import dev.maqueta.core.mesh.MeshBuilder;
import dev.maqueta.core.mesh.MeshData;
import dev.maqueta.core.mesh.TerrainMesher;
import dev.maqueta.core.mesh.TileSource;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
	private static ChunkColumns flat(int cx, int cz, int h, Material mat, int rgb) {
		ChunkColumns c = new ChunkColumns(cx, cz);
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++)
				c.set(x, z, h, h, mat, rgb, mat.subsurface(), 0x7A5A3A, 0xC8B080, 0, 0);
		return c;
	}

	@Test
	void paletteHasCuratedSizeAndNoPureExtremes() {
		int n = Palette.sampleCount();
		assertTrue(n >= 96 && n <= 128, "palette samples: " + n);
		for (Material m : Material.values()) {
			for (int src : new int[]{0x000000, 0xFFFFFF, 0x7FB238, 0x4040FF}) {
				int out = Palette.stylize(m, src, 0f);
				assertNotEquals(0x000000, out, m + " produced black");
				assertNotEquals(0xFFFFFF, out, m + " produced white");
			}
		}
	}

	@Test
	void oklabRoundTrip() {
		for (int rgb : new int[]{0x123456, 0x7FB238, 0xC8B080, 0x3F76E4}) {
			float[] lab = Palette.OkLab.fromRgb(rgb);
			int back = Palette.OkLab.toRgb(lab[0], lab[1], lab[2]);
			for (int s = 0; s < 24; s += 8) {
				assertTrue(Math.abs(((rgb >> s) & 0xFF) - ((back >> s) & 0xFF)) <= 1);
			}
		}
	}

	@Test
	void flatChunkMeshesTopsOnlyWhenSurrounded() {
		ColumnStore store = new ColumnStore();
		for (int z = -1; z <= 1; z++)
			for (int x = -1; x <= 1; x++)
				store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		MeshData mesh = new TerrainMesher(store, 40).build(0, 0, 0, 0, 0);
		assertEquals(256 * 4, mesh.vertexCount(), "a flat surrounded chunk is exactly 256 top quads");
		assertEquals(64f, mesh.minY());
		assertEquals(64f, mesh.maxY());
	}

	@Test
	void unexploredEdgeHasNoWalls() {
		ColumnStore store = new ColumnStore();
		store.put(flat(0, 0, 70, Material.GRASS, 0x7FB238));
		MeshData mesh = new TerrainMesher(store, 40).build(0, 0, 0, 0, 0);
		assertEquals(256 * 4, mesh.vertexCount(), "no cut-out walls towards unexplored land: the shader blends the edge");
	}

	/** Voxel chunk at (0,0): flat ground up to y 63, plus whatever the caller sets as opaque. */
	private static ColumnStore voxelWorld(int[][] extraOpaque) {
		ColumnStore store = new ColumnStore();
		for (int z = -1; z <= 1; z++)
			for (int x = -1; x <= 1; x++)
				if (x != 0 || z != 0) store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		ChunkColumns c = flat(0, 0, 64, Material.GRASS, 0x7FB238);
		ChunkVoxels v = new ChunkVoxels(56, 40);
		int grass = v.entryIndex(new ChunkVoxels.Entry("minecraft:grass_block", Material.GRASS, 0x7FB238, 0, 0));
		int stone = v.entryIndex(new ChunkVoxels.Entry("minecraft:stone", Material.STONE, 0x7F7F7F, 0, 0));
		java.util.TreeMap<Integer, int[]> shell = new java.util.TreeMap<>();
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++)
				for (int y = 56; y < 64; y++) v.setOpaque(x, y, z);
		for (int[] p : extraOpaque) v.setOpaque(p[0], p[1], p[2]);
		// The shell: every opaque cell with a non-opaque neighbour inside the chunk.
		for (int y = 56; y < 96; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++) {
					if (!v.opaque(x, y, z)) continue;
					boolean open = !v.opaque(x, y + 1, z) || (x > 0 && !v.opaque(x - 1, y, z)) || (x < 15 && !v.opaque(x + 1, y, z))
						|| (z > 0 && !v.opaque(x, y, z - 1)) || (z < 15 && !v.opaque(x, y, z + 1)) || !v.opaque(x, y - 1, z);
					if (open) shell.put(ChunkVoxels.cell(x, y - 56, z), new int[]{x, y, z, y == 63 ? grass : stone});
				}
		for (int[] s : shell.values()) v.addShell(s[0], s[1], s[2], s[3]);
		c.voxels = v;
		for (int[] p : extraOpaque) c.top[ChunkColumns.index(p[0], p[2])] = (short) Math.max(c.top[ChunkColumns.index(p[0], p[2])], p[1] + 1);
		store.put(c);
		return store;
	}

	@Test
	void voxelFloatingBlockIsACubeNotAPillar() {
		MeshData mesh = new TerrainMesher(voxelWorld(new int[][]{{8, 74, 8}}), 40).build(0, 0, 0, 0, 0);
		assertEquals((256 + 6) * 4, mesh.vertexCount(), "256 ground tops + the floating cube's 6 faces, nothing under it");
		assertEquals(75f, mesh.maxY());
		assertEquals(64f, mesh.minY(), "no geometry below the ground surface");
	}

	@Test
	void voxelHollowTowerKeepsItsInside() {
		java.util.List<int[]> walls = new java.util.ArrayList<>();
		for (int y = 64; y < 80; y++)
			for (int x = 4; x <= 8; x++)
				for (int z = 4; z <= 8; z++)
					if (x == 4 || x == 8 || z == 4 || z == 8) walls.add(new int[]{x, y, z});
		MeshData mesh = new TerrainMesher(voxelWorld(walls.toArray(new int[0][])), 40).build(0, 0, 0, 0, 0);
		// The floor inside the tower (3x3) still gets its top faces: the inside is air, not filled.
		ByteBufferCheck check = new ByteBufferCheck(mesh);
		assertTrue(check.hasTopFaceAt(6.5f, 64f, 6.5f), "the floor inside the hollow tower is visible");
	}

	/** Finds an up-facing quad covering a point in a mesh. */
	private record ByteBufferCheck(MeshData mesh) {
		boolean hasTopFaceAt(float x, float y, float z) {
			java.nio.ByteBuffer b = mesh.vertices();
			for (int q = 0; q < mesh.vertexCount() / 4; q++) {
				float minX = 1e9f, maxX = -1e9f, minZ = 1e9f, maxZ = -1e9f, yy = 0;
				boolean up = true;
				for (int k = 0; k < 4; k++) {
					int o = (q * 4 + k) * MeshBuilder.STRIDE;
					float vx = b.getFloat(o), vy = b.getFloat(o + 4), vz = b.getFloat(o + 8);
					up &= (b.get(o + 15) & 7) == MeshBuilder.N_UP;
					minX = Math.min(minX, vx); maxX = Math.max(maxX, vx); minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
					yy = vy;
				}
				if (up && Math.abs(yy - y) < 1e-3 && x > minX && x < maxX && z > minZ && z < maxZ) return true;
			}
			return false;
		}
	}

	@Test
	void riversStayWaterAtDistance() {
		ColumnStore store = new ColumnStore();
		ChunkColumns c = flat(0, 0, 64, Material.SAND, 0xDDCC99);
		// Half the chunk is a river: water surface 62 over a bed at 60, lower than the 64 banks.
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 8; x++)
				c.set(x, z, 62, 60, Material.WATER, 0x3F76E4, Material.SAND, 0xDDCC99, 0xDDCC99, 0, 0);
		store.put(c);
		MeshData coarse = new TerrainMesher(store, 40).build(0, 0, 3, 0, 0);
		assertEquals(62f, coarse.minY(), "the river cell keeps the water surface, not a hole down to the bed");
	}

	@Test
	void floatingBranchStaysInTheAir() {
		ColumnStore store = new ColumnStore();
		for (int z = -1; z <= 1; z++)
			for (int x = -1; x <= 1; x++)
				store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		ChunkColumns c = store.get(0, 0);
		// A single leaf block hanging at y 74 (10 blocks above the ground), air in between.
		int i = ChunkColumns.index(8, 8);
		c.top[i] = 75;
		c.beginRuns(i);
		c.addRun(75, 74, c.blockIndex("minecraft:oak_leaves"), Material.FOLIAGE, 0x59AE30);
		MeshData mesh = new TerrainMesher(store, 40).build(0, 0, 0, 0, 0);
		assertEquals(75f, mesh.maxY());
		assertEquals(64f, mesh.minY());
		// 256 ground tops (the one under the leaf still sees air) + the leaf cube: top, bottom, 4 sides.
		assertEquals((256 + 6) * 4, mesh.vertexCount(), "a floating block is a cube, not a pillar");
	}

	@Test
	void partialBlocksUseTheirShape() {
		ColumnStore store = new ColumnStore();
		for (int z = -1; z <= 1; z++)
			for (int x = -1; x <= 1; x++)
				store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		ChunkColumns c = store.get(0, 0);
		int i = ChunkColumns.index(3, 3);
		c.top[i] = 65;
		c.beginRuns(i);
		c.addRun(65, 64, c.blockIndex("minecraft:white_carpet"), Material.BUILT, 0xE9ECEC);
		TileSource carpet = new TileSource() {
			public int tile(String block, boolean top) { return 0; }
			public float[][] shape(String block) {
				return block.contains("carpet") ? new float[][]{{0, 0, 0, 1, 0.0625f, 1}} : null;
			}
		};
		MeshData mesh = new TerrainMesher(store, 40, carpet).build(0, 0, 0, 0, 0);
		assertEquals(64.0625f, mesh.maxY(), 1e-4, "a carpet is 1/16 of a block, not a cube");
	}

	@Test
	void routeGoesAroundACliffAndNeverClimbsTwoBlocks() {
		ColumnStore store = new ColumnStore();
		for (int z = -1; z <= 2; z++)
			for (int x = -1; x <= 2; x++)
				store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		// A 6-block wall across x = 16, open only at z >= 40.
		for (int z = -16; z < 40; z++) {
			ChunkColumns c = store.get(1, z >> 4);
			c.top[ChunkColumns.index(0, z & 15)] = 70;
			c.floor[ChunkColumns.index(0, z & 15)] = 70;
		}
		dev.maqueta.core.route.RoutePlanner.Route r = dev.maqueta.core.route.RoutePlanner.find(store, 8.5, 8.5, 24.5, 8.5,
			dev.maqueta.core.route.RoutePlanner.Options.WALK);
		assertTrue(r.complete());
		assertTrue(r.length() > 60, "must detour around the wall, got " + r.length());
		for (double[] p : r.points()) assertTrue(p[1] <= 64.01, "never walks on the wall");
		double[] last = r.points().getLast();
		assertEquals(24.5, last[0], 0.6);
	}

	@Test
	void caveSurroundingsAreShadedNotHidden() {
		dev.maqueta.core.cave.VoxelVolume v = new dev.maqueta.core.cave.VoxelVolume(0, 0, 0, 16, 8, 16);
		for (int y = 0; y < 8; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++)
					v.set(x, y, z, Material.STONE, 0x808080, 0);
		for (int x = 2; x <= 5; x++) v.set(x, 3, 3, Material.NONE, 0, 0);
		for (int x = 10; x <= 13; x++) v.set(x, 3, 12, Material.NONE, 0, 0);
		int fair = dev.maqueta.core.cave.CaveMesher.build(v, 3, 3, 3, 6, 10_000, 0, 0, false).mesh().vertexCount();
		int full = dev.maqueta.core.cave.CaveMesher.build(v, 3, 3, 3, 6, 10_000, 0, 0, true).mesh().vertexCount();
		assertTrue(full > fair * 4, "surroundings add the other cave and the rock section");
	}

	@Test
	void columnsRoundTripNames() throws Exception {
		ChunkColumns c = flat(0, 0, 64, Material.GRASS, 0x7FB238);
		c.setNames(3, 4, "minecraft:grass_block", "minecraft:dirt", "minecraft:dirt", "minecraft:plains", ChunkColumns.FLAG_PATH);
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		c.write(new java.io.DataOutputStream(bo));
		ChunkColumns b = ChunkColumns.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bo.toByteArray())));
		int i = ChunkColumns.index(3, 4);
		assertEquals("minecraft:grass_block", b.topName(i));
		assertEquals("minecraft:plains", b.biomeName(i));
		assertTrue(b.hasFlag(i, ChunkColumns.FLAG_PATH));
	}

	@Test
	void lodReducesGeometry() {
		ColumnStore store = new ColumnStore();
		ChunkColumns c = flat(0, 0, 64, Material.GRASS, 0x7FB238);
		for (int i = 0; i < 256; i += 3) c.top[i] = (short) (64 + (i % 7));
		store.put(c);
		int lod0 = new TerrainMesher(store, 40).build(0, 0, 0, 0, 0).vertexCount();
		int lod2 = new TerrainMesher(store, 40).build(0, 0, 2, 0, 0).vertexCount();
		assertTrue(lod2 < lod0 / 4, "lod2=" + lod2 + " lod0=" + lod0);
	}

	@Test
	void sunCastsShadowBehindTallWall() {
		ColumnStore store = new ColumnStore();
		for (int z = -3; z <= 3; z++)
			for (int x = -3; x <= 3; x++)
				store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		// A tall wall north-west of the origin: the sun comes from the north-west.
		ChunkColumns wall = store.get(-1, -1);
		for (int i = 0; i < 256; i++) wall.top[i] = 100;
		HeightWindow w = new HeightWindow(store, 0, 0);
		assertTrue(LightBaker.sun(w, 2.5f, 64.05f, 2.5f) < 0.5f, "point behind wall is shadowed");
		assertEquals(1f, LightBaker.sun(w, 2.5f, 120f, 2.5f), 1e-6, "point above everything is lit");
		assertTrue(LightBaker.sky(w, 2.5f, 64f, 2.5f) < LightBaker.sky(w, 40.5f, 64f, 40.5f));
	}

	@Test
	void caveMesherOnlyShowsConnectedSpace() {
		VoxelVolume v = new VoxelVolume(0, 0, 0, 16, 8, 16);
		for (int y = 0; y < 8; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++)
					v.set(x, y, z, Material.STONE, 0x808080, 0);
		// Two separate pockets.
		for (int x = 2; x <= 5; x++) v.set(x, 3, 3, Material.NONE, 0, 0);
		for (int x = 10; x <= 13; x++) v.set(x, 3, 12, Material.NONE, 0, 0);
		v.set(5, 2, 3, Material.EMISSIVE, 0xFFCC66, 14);
		CaveMesher.Result connected = CaveMesher.build(v, 3, 3, 3, 6, 10_000, 0, 0);
		assertEquals(4, connected.reachedCells());
		assertFalse(connected.mesh().isEmpty());
		// Pocket alone yields a bounded number of faces (4 cells x up to 6 faces).
		assertTrue(connected.mesh().vertexCount() / 4 <= 4 * 6);
	}

	@Test
	void cameraIsReverseZ() {
		OrbitCamera cam = new OrbitCamera();
		cam.snapTo(100, 64, 100);
		cam.snapPose(0.3f, (float) Math.toRadians(50), 80);
		Matrix4f vp = cam.projection(16 / 9f, true).mul(cam.view(0, 0));
		Vector4f target = new Vector4f(100, 64, 100, 1).mul(vp);
		Vector4f near = new Vector4f(cam.eye(0, 0), 1).add(new Vector4f(cam.eyeDirection().mul(-2f), 0)).mul(vp);
		float dTarget = target.z / target.w, dNear = near.z / near.w;
		assertTrue(dTarget > 0 && dTarget < 1);
		assertTrue(dNear > dTarget, "closer points must have larger depth (reverse-Z)");
		assertEquals(0f, target.x / target.w, 1e-3);
		assertEquals(0f, target.y / target.w, 1e-3);
	}

	@Test
	void raycastFindsGround() {
		ColumnStore store = new ColumnStore();
		for (int z = -2; z <= 2; z++)
			for (int x = -2; x <= 2; x++)
				store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		OrbitCamera cam = new OrbitCamera();
		cam.snapTo(8, 64, 8);
		cam.snapPose(0f, (float) Math.toRadians(60), 50);
		Matrix4f vp = cam.projection(1f, true).mul(cam.view(0, 0));
		double[] hit = HeightfieldRaycast.cast(store, OrbitCamera.ray(vp, 0, 0, true), 0, 0, 500);
		assertNotNull(hit);
		assertEquals(8, hit[0], 1.0);
		assertEquals(64, hit[1], 0.6);
		assertEquals(8, hit[2], 1.0);
	}

	@Test
	void regionRoundTrip(@TempDir Path dir) throws Exception {
		RegionStorage storage = new RegionStorage(dir);
		ChunkColumns a = flat(3, -4, 72, Material.SAND, 0xDDCC99);
		a.decoColor[17] = 0xFF00AA;
		a.capturedAt = 1234;
		storage.write(0, -1, List.of(a));
		List<ChunkColumns> back = storage.read(0, -1);
		assertEquals(1, back.size());
		ChunkColumns b = back.getFirst();
		assertEquals(3, b.chunkX);
		assertEquals(-4, b.chunkZ);
		assertArrayEquals(a.top, b.top);
		assertArrayEquals(a.color, b.color);
		assertEquals(0xFF00AA, b.decoColor[17]);
		assertEquals(1, storage.listRegions().size());
	}

	@Test
	void waypointsSerialiseAndShare() {
		Waypoint w = new Waypoint("Base; norte", 10, 64, -20, 0x3FB6C9, "minecraft:overworld", Waypoint.FLAG_DEATH);
		Waypoint back = Waypoint.parse(w.serialize());
		assertNotNull(back);
		assertEquals("Base; norte", back.name);
		assertTrue(back.isDeath());
		Waypoint shared = Waypoint.fromShare("look " + w.shareText() + " here", 0xFFFFFF);
		assertNotNull(shared);
		assertEquals(-20, shared.z);
	}

	@Test
	void trailSkipsSmallStepsAndWraps() {
		Trail t = new Trail(4, 1f);
		t.record(0, 0, 0);
		t.record(0.2, 0, 0);
		assertEquals(1, t.size());
		for (int i = 1; i <= 6; i++) t.record(i * 2, 0, 0);
		assertEquals(4, t.size());
		assertEquals(6f, t.snapshot()[0]);
	}

	@Test
	void timeGradingDayBrighterThanNight() {
		TimeGrading.Grade day = TimeGrading.grade(TimeGrading.Dimension.OVERWORLD, 6000, 0, 0);
		TimeGrading.Grade night = TimeGrading.grade(TimeGrading.Dimension.OVERWORLD, 18000, 0, 0);
		assertTrue(day.sun()[1] > night.sun()[1]);
		assertTrue(night.emission()[0] > day.emission()[0]);
		assertEquals("12:00", TimeGrading.clock(6000, false));
		assertEquals("12:00 AM", TimeGrading.clock(18000, true));
	}

	@Test
	void hudAnchorsPlaceInsideScreen() {
		for (HudAnchor a : HudAnchor.values()) {
			int[] p = a.place(800, 600, 120, 120, 8, 8);
			assertTrue(p[0] >= 0 && p[0] + 120 <= 800, a.name());
			assertTrue(p[1] >= 0 && p[1] + 120 <= 600, a.name());
		}
	}

	@Test
	void meshBuilderStride() {
		MeshBuilder mb = new MeshBuilder(1);
		for (int i = 0; i < 8; i++) mb.vertex(i, i, i, 0x112233, 1, 1, 1, 1, 0);
		MeshData d = MeshData.of(mb);
		assertEquals(8 * MeshBuilder.STRIDE, d.vertices().remaining());
		assertEquals(12, d.indexCount());
	}
}
