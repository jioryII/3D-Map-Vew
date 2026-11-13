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
	void unexploredEdgeGetsStrataWalls() {
		ColumnStore store = new ColumnStore();
		store.put(flat(0, 0, 70, Material.GRASS, 0x7FB238));
		MeshData mesh = new TerrainMesher(store, 40).build(0, 0, 0, 0, 0);
		assertTrue(mesh.vertexCount() > 256 * 4, "edges facing unexplored land show cut-out walls");
		assertEquals(40f, mesh.minY());
	}

	@Test
	void treeCanopyFloatsAboveGround() {
		ColumnStore store = new ColumnStore();
		for (int z = -1; z <= 1; z++)
			for (int x = -1; x <= 1; x++)
				store.put(flat(x, z, 64, Material.GRASS, 0x7FB238));
		ChunkColumns c = store.get(0, 0);
		// A 3x3 canopy at y 70, ground at 64.
		for (int z = 6; z <= 8; z++)
			for (int x = 6; x <= 8; x++)
				c.set(x, z, 70, 64, Material.FOLIAGE, 0x59AE30, Material.GRASS, 0x7FB238, 0x7FB238, 0, 0);
		MeshData mesh = new TerrainMesher(store, 40).build(0, 0, 0, 0, 0);
		assertEquals(70f, mesh.maxY());
		assertEquals(64f, mesh.minY(), "no pillar walls down to the board: the lowest geometry is the ground");
		// Ground quads under the canopy + canopy tops + undersides + 12 canopy side quads.
		int expectedQuads = 247 + 9 * 3 + 12;
		assertEquals(expectedQuads * 4, mesh.vertexCount());
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
