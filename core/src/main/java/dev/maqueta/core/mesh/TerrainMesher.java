package dev.maqueta.core.mesh;

import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ChunkVoxels;
import dev.maqueta.core.ColumnStore;
import dev.maqueta.core.Material;
import dev.maqueta.core.Palette;
import dev.maqueta.core.light.HeightWindow;
import dev.maqueta.core.light.LightBaker;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns explored layered columns into the maqueta.
 * <ul>
 *   <li><b>Detail level (lod 0)</b>: every run of blocks is drawn as it is, with air kept as
 *       air: faces only where they touch real air. Branches, overhangs, bridges and floating
 *       blocks stay where they are. Blocks that are not full cubes (stairs, slabs, carpets,
 *       torches, lanterns, vines, bamboo…) use their real boxes.</li>
 *   <li><b>Coarse levels (lod 1-3)</b>: {@code 2^lod} columns per cell, top surface only, the
 *       tallest column wins so buildings never vanish first.</li>
 * </ul>
 * No walls are ever built towards unexplored land: the shader lets the edge melt into the
 * table as a relief instead.
 */
public final class TerrainMesher {
	private static final int[][] DIRS = {{0, -1, MeshBuilder.N_NORTH}, {0, 1, MeshBuilder.N_SOUTH}, {-1, 0, MeshBuilder.N_WEST}, {1, 0, MeshBuilder.N_EAST}};
	private static final String STONE = "minecraft:stone";
	private static final int UNKNOWN = -1, AIR = 0, SOLID = 1, WATER = 2;

	private final ColumnStore store;
	private final int boardY;
	private final TileSource tiles;
	private final Map<String, float[][]> shapes = new HashMap<>();

	public TerrainMesher(ColumnStore store, int boardY) {
		this(store, boardY, TileSource.NONE);
	}

	public TerrainMesher(ColumnStore store, int boardY, TileSource tiles) {
		this.store = store;
		this.boardY = boardY;
		this.tiles = tiles;
	}

	private final ChunkColumns[] neighborhood = new ChunkColumns[9];
	private int neighborhoodX, neighborhoodZ;

	public static final int EDGE_RANGE = 8;

	public MeshData build(int chunkX, int chunkZ, int lod, int anchorX, int anchorZ) {
		neighborhoodX = chunkX - 1;
		neighborhoodZ = chunkZ - 1;
		for (int z = 0; z < 3; z++) {
			for (int x = 0; x < 3; x++) neighborhood[z * 3 + x] = store.get(neighborhoodX + x, neighborhoodZ + z);
		}
		ChunkColumns self = neighborhood[4];
		if (self == null) return MeshData.EMPTY;
		HeightWindow window = new HeightWindow(store, chunkX, chunkZ);
		MeshData data = lod == 0
			? (self.voxels != null ? buildVoxels(chunkX, chunkZ, anchorX, anchorZ, window) : buildDetailed(chunkX, chunkZ, anchorX, anchorZ, window))
			: (self.voxels != null ? buildVoxelCoarse(chunkX, chunkZ, lod, anchorX, anchorZ, window) : buildCoarse(chunkX, chunkZ, lod, anchorX, anchorZ, window));
		if (!data.isEmpty()) stampEdgeDistance(data, chunkX, chunkZ, anchorX, anchorZ, window);
		return data;
	}

	private ChunkColumns nearby(int chunkX, int chunkZ) {
		int x = chunkX - neighborhoodX, z = chunkZ - neighborhoodZ;
		return x >= 0 && x < 3 && z >= 0 && z < 3 ? neighborhood[z * 3 + x] : store.get(chunkX, chunkZ);
	}

	/**
	 * Writes into every vertex how far (0..{@value #EDGE_RANGE} blocks) it is from unexplored
	 * land, in the high byte of the aux field. The vertex shader uses it to let the terrain
	 * melt down into the table at the border of what has been explored (a carved relief
	 * instead of cut-out walls).
	 */
	private void stampEdgeDistance(MeshData data, int chunkX, int chunkZ, int anchorX, int anchorZ, HeightWindow window) {
		// Include the halo so the two sweeps see the same edges as the old ring search.
		int halo = EDGE_RANGE;
		int ox = (chunkX << 4) - 2 * halo - 1, oz = (chunkZ << 4) - 2 * halo - 1;
		int size = 16 + 4 * halo + 2;
		int[] dist = new int[size * size];
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int i = z * size + x;
				int d = window.top(ox + x, oz + z) == ChunkColumns.UNKNOWN ? 0 : EDGE_RANGE;
				if (x > 0) d = Math.min(d, dist[i - 1] + 1);
				if (z > 0) {
					d = Math.min(d, dist[i - size] + 1);
					if (x > 0) d = Math.min(d, dist[i - size - 1] + 1);
					if (x + 1 < size) d = Math.min(d, dist[i - size + 1] + 1);
				}
				dist[i] = d;
			}
		}
		for (int z = size - 1; z >= 0; z--) {
			for (int x = size - 1; x >= 0; x--) {
				int i = z * size + x;
				int d = dist[i];
				if (x + 1 < size) d = Math.min(d, dist[i + 1] + 1);
				if (z + 1 < size) {
					d = Math.min(d, dist[i + size] + 1);
					if (x > 0) d = Math.min(d, dist[i + size - 1] + 1);
					if (x + 1 < size) d = Math.min(d, dist[i + size + 1] + 1);
				}
				dist[i] = d;
			}
		}
		java.nio.ByteBuffer buf = data.vertices();
		for (int v = 0; v < data.vertexCount(); v++) {
			int base = v * MeshBuilder.STRIDE;
			float x = buf.getFloat(base) + anchorX, z = buf.getFloat(base + 8) + anchorZ;
			// The corner (or point) touches up to four columns; take the closest edge among them.
			int cx = (int) Math.floor(x - 0.001f), cz = (int) Math.floor(z - 0.001f);
			int best = EDGE_RANGE;
			for (int dz = 0; dz <= 1; dz++) {
				for (int dx = 0; dx <= 1; dx++) {
					int px = Math.abs(x - Math.round(x)) < 0.002f ? Math.round(x) - 1 + dx : cx;
					int pz = Math.abs(z - Math.round(z)) < 0.002f ? Math.round(z) - 1 + dz : cz;
					int ix = px - ox, iz = pz - oz;
					if (ix < halo || iz < halo || ix >= size - halo || iz >= size - halo) continue;
					best = Math.min(best, dist[iz * size + ix]);
				}
			}
			int auxPos = base + 22;
			int mat = buf.getShort(auxPos) & 0xF0FF; // keep the material and the flag bits (12+)
			buf.putShort(auxPos, (short) (mat | (best << 8)));
		}
	}

	// ------------------------------------------------------------------ shared helpers

	private float[][] shape(String block) {
		if (block.isEmpty()) return null;
		return shapes.computeIfAbsent(block, tiles::shape);
	}

	private boolean isShaped(String block) {
		float[][] s = shape(block);
		return s != null && s.length > 0;
	}

	/** What occupies a block cell: UNKNOWN (unexplored), AIR, SOLID (full, opaque) or WATER. */
	private int cellAt(int x, int y, int z) {
		ChunkColumns c = nearby(x >> 4, z >> 4);
		if (c == null) return UNKNOWN;
		int i = ChunkColumns.index(x & 15, z & 15);
		if (!c.isKnown(i)) return UNKNOWN;
		if (y < c.floor[i]) return SOLID;
		int n = c.runCount(i);
		for (int k = 0; k < n; k++) {
			if (y >= c.runBottom(i, k) && y < c.runTop(i, k)) {
				if (c.runMaterial(i, k) == Material.WATER) return WATER;
				return isShaped(c.runBlock(i, k)) ? AIR : SOLID;
			}
		}
		return AIR;
	}

	private boolean opaque(int x, int y, int z) {
		return cellAt(x, y, z) == SOLID;
	}

	private static boolean tinted(Material m) {
		return m == Material.GRASS || m == Material.FOLIAGE || m == Material.PLANT || m == Material.WATER;
	}

	private int base(Material mat, int sourceRgb, float var) {
		return Palette.harmonize(mat, sourceRgb, var);
	}

	private static float[] edge(int dir, float x0, float z0, float x1, float z1) {
		return switch (dir) {
			case MeshBuilder.N_NORTH -> new float[]{x0, z0, x1, z0};
			case MeshBuilder.N_SOUTH -> new float[]{x1, z1, x0, z1};
			case MeshBuilder.N_WEST -> new float[]{x0, z1, x0, z0};
			default -> new float[]{x1, z0, x1, z1};
		};
	}

	/** Classic voxel AO for a top face corner at block-level y (cells at height y around the corner). */
	private float topAo(int x, int y, int z, int sx, int sz) {
		return LightBaker.vertexAo(opaque(x + sx, y, z), opaque(x, y, z + sz), opaque(x + sx, y, z + sz));
	}

	// ------------------------------------------------------------------ voxel detail (like Minecraft)

	private static final int V_UNKNOWN = -1, V_AIR = 0, V_OPAQUE = 1, V_WATER = 2;
	private static final int[][] FACE6 = {
		{0, 1, 0, MeshBuilder.N_UP}, {0, -1, 0, MeshBuilder.N_DOWN},
		{0, 0, -1, MeshBuilder.N_NORTH}, {0, 0, 1, MeshBuilder.N_SOUTH},
		{-1, 0, 0, MeshBuilder.N_WEST}, {1, 0, 0, MeshBuilder.N_EAST},
	};

	/** What fills a world cell, looked up across chunks in the voxel grids. */
	private int voxelAt(int x, int y, int z) {
		ChunkColumns c = nearby(x >> 4, z >> 4);
		if (c == null) return V_UNKNOWN;
		ChunkVoxels v = c.voxels;
		if (v == null) {
			int i = ChunkColumns.index(x & 15, z & 15);
			if (!c.isKnown(i)) return V_UNKNOWN;
			return y < c.top[i] ? V_OPAQUE : V_AIR;
		}
		if (v.opaque(x & 15, y, z & 15)) return V_OPAQUE;
		return v.water(x & 15, y, z & 15) ? V_WATER : V_AIR;
	}

	private boolean vOpaque(int x, int y, int z) {
		return voxelAt(x, y, z) == V_OPAQUE;
	}

	private static final float[][][] FACE_QUADS = {
		{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
		{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
		{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},
		{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
		{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
		{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}}
	};

	/** Voxel corner AO: two side cells and the diagonal cell beyond the face. */
	private float cornerAo(int x, int y, int z, int[] face, float[] corner) {
		int fx = x + face[0], fy = y + face[1], fz = z + face[2];
		int sx = corner[0] > 0.5f ? 1 : -1;
		int sy = corner[1] > 0.5f ? 1 : -1;
		int sz = corner[2] > 0.5f ? 1 : -1;
		if (face[0] != 0) {
			return LightBaker.vertexAo(vOpaque(fx, fy + sy, fz), vOpaque(fx, fy, fz + sz), vOpaque(fx, fy + sy, fz + sz));
		}
		if (face[1] != 0) {
			return LightBaker.vertexAo(vOpaque(fx + sx, fy, fz), vOpaque(fx, fy, fz + sz), vOpaque(fx + sx, fy, fz + sz));
		}
		return LightBaker.vertexAo(vOpaque(fx + sx, fy, fz), vOpaque(fx, fy + sy, fz), vOpaque(fx + sx, fy + sy, fz));
	}

	private MeshData buildVoxels(int chunkX, int chunkZ, int anchorX, int anchorZ, HeightWindow window) {
		ChunkColumns c = nearby(chunkX, chunkZ);
		ChunkVoxels v = c.voxels;
		MeshBuilder mb = new MeshBuilder(2048);
		float[] colSky = new float[256];
		float[] colEmit = new float[256];
		for (int i = 0; i < 256; i++) {
			if (!c.isKnown(i)) continue;
			int wx = (chunkX << 4) + (i & 15), wz = (chunkZ << 4) + (i >> 4);
			colSky[i] = LightBaker.sky(window, wx + 0.5f, c.top[i], wz + 0.5f);
			colEmit[i] = LightBaker.emission(window, wx, wz, c.top[i]);
		}
		// Block light on an 18x18 grid of columns (this chunk and its ring), interpolated per vertex.
		float[] emitGrid = new float[18 * 18];
		for (int gz = 0; gz < 18; gz++) {
			for (int gx = 0; gx < 18; gx++) {
				int wx = (chunkX << 4) + gx - 1, wz = (chunkZ << 4) + gz - 1;
				int t = window.top(wx, wz);
				emitGrid[gz * 18 + gx] = t == ChunkColumns.UNKNOWN ? 0f : LightBaker.emission(window, wx, wz, t);
			}
		}
		float[] ao = new float[4];
		for (int k = 0; k < v.shellCount(); k++) {
			int lx = v.shellX(k), lz = v.shellZ(k), y = v.shellY(k);
			int i = ChunkColumns.index(lx, lz);
			int wx = (chunkX << 4) + lx, wz = (chunkZ << 4) + lz;
			ChunkVoxels.Entry e = v.shellEntry(k);
			Material mat = e.material();
			float var = Palette.variation(wx * 7 + y, wz);
			int src = v.sourceColor(e, lx, lz);
			// Under something (a roof, a canopy, an overhang): less sky.
			float sky = y + 1 >= c.top[i] ? colSky[i] : colSky[i] * 0.55f;
			boolean glow = mat == Material.EMISSIVE || mat == Material.LAVA;
			float emit = glow ? 1f : colEmit[i];
			float x0 = wx - anchorX, z0 = wz - anchorZ;
			if (mat == Material.WATER) {
				emitVoxelWater(mb, wx, y, wz, x0, z0, src, window, sky, emit);
				continue;
			}
			if (isShaped(e.key())) {
				emitShape(mb, e.key(), mat, src, var, wx, y, wz, x0, z0, window, sky, emit);
				continue;
			}
			int top = base(mat, src, mat.isBuilt() ? var * 0.25f : var * 0.6f);
			int sideAvg = tinted(mat) ? -1 : tiles.color(e.key(), false);
			int side = sideAvg >= 0 ? base(mat, sideAvg, mat.isBuilt() ? var * 0.25f : var * 0.6f) : top;
			if (mat == Material.GRASS && tiles.color(e.key(), false) >= 0) {
				side = Palette.mix(base(Material.DIRT, tiles.color(e.key(), false), var * 0.4f), top, 0.22f);
			}
			int flags = mat.isBuilt() ? MeshBuilder.FLAG_BUILT : 0;
			int tileTop = tiles.tile(e.key(), true), tileSide = tiles.tile(e.key(), false);
			if (voxelAt(wx, y + 1, wz) == V_OPAQUE) {
				// Section cap: the hidden top of a block buried under another one. The shader
				// shows it only when the see-through cutaway lifts the block above away.
				mb.texture(tileTop, mat.ordinal() | MeshBuilder.AUX_CAP);
				for (float[] p : FACE_QUADS[MeshBuilder.N_UP]) {
					mb.vertex(x0 + p[0], y + p[1], z0 + p[2], top, MeshBuilder.N_UP | flags, 0f, sky * 0.6f, 0.8f, colEmit[i]);
				}
			}
			for (int[] f : FACE6) {
				int n = voxelAt(wx + f[0], y + f[1], wz + f[2]);
				if (n == V_OPAQUE || n == V_WATER) continue;
				if (n == V_UNKNOWN && f[1] != 0) continue;
				boolean vertical = f[3] == MeshBuilder.N_UP || f[3] == MeshBuilder.N_DOWN;
				int color = f[3] == MeshBuilder.N_DOWN ? Palette.shade(side, -0.12f) : (vertical ? top : side);
				float sun = f[3] == MeshBuilder.N_DOWN ? 0f
					: LightBaker.sun(window, wx + 0.5f + f[0] * 0.6f, y + 0.5f + f[1] * 0.6f, wz + 0.5f + f[2] * 0.6f);
				float faceSky = f[3] == MeshBuilder.N_DOWN ? sky * 0.5f : (vertical ? sky : sky * 0.8f);
				mb.texture(vertical ? tileTop : tileSide, mat.ordinal());
				float[][] quad = FACE_QUADS[f[3]];
				for (int q = 0; q < 4; q++) ao[q] = n == V_UNKNOWN ? 0.95f : cornerAo(wx, y, wz, f, quad[q]);
				int start = ao[0] + ao[2] < ao[1] + ao[3] ? 1 : 0;
				for (int q = 0; q < 4; q++) {
					int qq = (q + start) & 3;
					float[] p = quad[qq];
					float ve = glow ? 1f : cornerEmit(emitGrid, lx + (int) p[0], lz + (int) p[2]);
					mb.vertex(x0 + p[0], y + p[1], z0 + p[2], color, f[3] | flags, sun, faceSky, ao[qq], ve);
				}
			}
		}
		emitSkirts(mb, c, chunkX, chunkZ, anchorX, anchorZ, window);
		return MeshData.of(mb);
	}

	/**
	 * Below the voxel band this chunk is solid rock. When a neighbour's ground lies lower
	 * than that (a valley captured after this mountain), the rock face between them would be
	 * missing and the valley would look straight through the mountain. Close it with plain
	 * rock walls, down to wherever the neighbour is solid again.
	 */
	private void emitSkirts(MeshBuilder mb, ChunkColumns c, int chunkX, int chunkZ, int anchorX, int anchorZ, HeightWindow window) {
		ChunkVoxels v = c.voxels;
		int stoneTile = tiles.tile(STONE, false);
		int stoneSrc = tiles.color(STONE, false) >= 0 ? tiles.color(STONE, false) : 0x7F7F7F;
		for (int[] f : FACE6) {
			if (f[1] != 0) continue;
			for (int k = 0; k < 16; k++) {
				int lx = f[0] < 0 ? 0 : (f[0] > 0 ? 15 : k);
				int lz = f[2] < 0 ? 0 : (f[2] > 0 ? 15 : k);
				int wx = (chunkX << 4) + lx, wz = (chunkZ << 4) + lz;
				int nx = wx + f[0], nz = wz + f[2];
				ChunkColumns n = nearby(nx >> 4, nz >> 4);
				if (n == null) continue;
				int ni = ChunkColumns.index(nx & 15, nz & 15);
				if (!n.isKnown(ni)) continue;
				int low = n.voxels != null ? n.voxels.yMin : n.floor[ni];
				if (low >= v.yMin) continue;
				int sub = c.subColor[ChunkColumns.index(lx, lz)];
				int color = base(Material.STONE, sub != 0 ? Palette.mix(sub, stoneSrc, 0.5f) : stoneSrc, Palette.variation(wx, wz) * 0.3f);
				float x0 = wx - anchorX, z0 = wz - anchorZ;
				float[][] quad = FACE_QUADS[f[3]];
				for (int y = v.yMin - 1; y >= low; y--) {
					int nv = voxelAt(nx, y, nz);
					if (nv == V_OPAQUE) continue;
					float sun = LightBaker.sun(window, wx + 0.5f + f[0] * 0.6f, y + 0.5f, wz + 0.5f + f[2] * 0.6f);
					mb.texture(stoneTile, Material.STONE.ordinal());
					for (float[] p : quad) mb.vertex(x0 + p[0], y + p[1], z0 + p[2], color, f[3], sun, 0.6f, 0.9f, 0f);
				}
			}
		}
	}

	private void emitVoxelWater(MeshBuilder mb, int wx, int y, int wz, float x0, float z0, int src, HeightWindow window, float sky, float emit) {
		int water = Palette.harmonize(Material.WATER, src, 0f);
		int depth = 0;
		while (depth < 12 && voxelAt(wx, y - depth - 1, wz) == V_WATER) depth++;
		int step = Math.min(5, (depth + 2) / 2);
		int top = Palette.shade(water, 0.06f - step * 0.045f);
		if (voxelAt(wx, y + 1, wz) == V_AIR) {
			float sun = LightBaker.sun(window, wx + 0.5f, y + 1.05f, wz + 0.5f);
			int[][] corners = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
			float[] vx = {x0, x0, x0 + 1, x0 + 1};
			float[] vz = {z0, z0 + 1, z0 + 1, z0};
			mb.texture(0, Material.WATER.ordinal());
			for (int k = 0; k < 4; k++) {
				int sx = corners[k][0], sz = corners[k][1];
				int shore = (vOpaque(wx + sx, y, wz) ? 1 : 0) + (vOpaque(wx, y, wz + sz) ? 1 : 0) + (vOpaque(wx + sx, y, wz + sz) ? 1 : 0);
				float open = shore == 0 ? 1f : (shore == 1 ? 0.45f : 0.15f);
				mb.vertex(vx[k], y + 1, vz[k], top, MeshBuilder.N_UP | MeshBuilder.FLAG_WATER, sun, sky, open, emit);
			}
		}
		int side = Palette.shade(water, -0.10f);
		for (int[] d : DIRS) {
			if (voxelAt(wx + d[0], y, wz + d[1]) != V_AIR) continue;
			float[] ed = edge(d[2], x0, z0, x0 + 1, z0 + 1);
			mb.texture(0, Material.WATER.ordinal());
			mb.vertex(ed[0], y + 1, ed[1], side, d[2] | MeshBuilder.FLAG_WATER, 0.8f, sky * 0.8f, 1f, emit);
			mb.vertex(ed[0], y, ed[1], side, d[2] | MeshBuilder.FLAG_WATER, 0.8f, sky * 0.8f, 1f, emit);
			mb.vertex(ed[2], y, ed[3], side, d[2] | MeshBuilder.FLAG_WATER, 0.8f, sky * 0.8f, 1f, emit);
			mb.vertex(ed[2], y + 1, ed[3], side, d[2] | MeshBuilder.FLAG_WATER, 0.8f, sky * 0.8f, 1f, emit);
		}
	}

	// ------------------------------------------------------------------ detail level

	private MeshData buildDetailed(int chunkX, int chunkZ, int anchorX, int anchorZ, HeightWindow window) {
		ChunkColumns c = nearby(chunkX, chunkZ);
		MeshBuilder mb = new MeshBuilder(1024);
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = ChunkColumns.index(lx, lz);
				if (!c.isKnown(i)) continue;
				int wx = (chunkX << 4) + lx, wz = (chunkZ << 4) + lz;
				float x0 = wx - anchorX, z0 = wz - anchorZ, x1 = x0 + 1, z1 = z0 + 1;
				float colSky = LightBaker.sky(window, wx + 0.5f, c.top[i], wz + 0.5f);
				float emit = LightBaker.emission(window, wx, wz, c.top[i]);
				float var = Palette.variation(wx, wz);
				int n = c.runCount(i);
				for (int k = 0; k < n; k++) {
					int t = c.runTop(i, k), b = c.runBottom(i, k);
					String block = c.runBlock(i, k);
					Material mat = c.runMaterial(i, k);
					int rgb = c.runColor(i, k);
					boolean topmost = k == 0;
					float sky = topmost ? colSky : colSky * 0.65f;
					if (mat == Material.WATER) {
						emitWater(mb, c, i, wx, wz, x0, z0, x1, z1, t, b, rgb, window, sky, emit);
					} else if (isShaped(block)) {
						for (int y = b; y < t; y++) emitShape(mb, block, mat, rgb, var, wx, y, wz, x0, z0, window, sky, emit);
					} else {
						emitRun(mb, block, mat, rgb, var, wx, wz, x0, z0, x1, z1, t, b, window, sky, emit);
					}
				}
				emitGround(mb, c, i, wx, wz, x0, z0, x1, z1, window, n == 0 ? colSky : colSky * 0.68f, emit, var);
			}
		}
		return MeshData.of(mb);
	}

	/** A run of full blocks: top and bottom where open, sides only towards real air. */
	private void emitRun(MeshBuilder mb, String block, Material mat, int rgb, float var, int wx, int wz,
						 float x0, float z0, float x1, float z1, int t, int b, HeightWindow window, float sky, float emit) {
		int color = base(mat, rgb, mat.isBuilt() ? var * 0.25f : var * 0.6f);
		// Sides wear their own texture colour (bark on logs, not the rings on top), except biome-tinted blocks.
		int sideAvg = tinted(mat) ? -1 : tiles.color(block, false);
		int sideColor = sideAvg >= 0 ? base(mat, sideAvg, mat.isBuilt() ? var * 0.25f : var * 0.6f) : color;
		int flags = mat.isBuilt() ? MeshBuilder.FLAG_BUILT : 0;
		boolean glow = mat == Material.EMISSIVE || mat == Material.LAVA;
		float e = glow ? 1f : emit;
		if (!opaque(wx, t, wz) && cellAt(wx, t, wz) != WATER) {
			float sun = LightBaker.sun(window, wx + 0.5f, t + 0.05f, wz + 0.5f);
			mb.texture(tiles.tile(block, true), mat.ordinal());
			topQuad(mb, wx, t, wz, x0, z0, x1, z1, color, flags, sun, sky, e);
		}
		if (cellAt(wx, b - 1, wz) == AIR) {
			mb.texture(tiles.tile(block, true), mat.ordinal());
			int under = Palette.shade(color, -0.12f);
			mb.vertex(x0, b, z0, under, MeshBuilder.N_DOWN | flags, 0f, sky * 0.5f, 0.8f, e);
			mb.vertex(x1, b, z0, under, MeshBuilder.N_DOWN | flags, 0f, sky * 0.5f, 0.8f, e);
			mb.vertex(x1, b, z1, under, MeshBuilder.N_DOWN | flags, 0f, sky * 0.5f, 0.8f, e);
			mb.vertex(x0, b, z1, under, MeshBuilder.N_DOWN | flags, 0f, sky * 0.5f, 0.8f, e);
		}
		mb.texture(tiles.tile(block, false), mat.ordinal());
		for (int[] d : DIRS) {
			int nx = wx + d[0], nz = wz + d[1];
			float[] ed = edge(d[2], x0, z0, x1, z1);
			int y = b;
			while (y < t) {
				if (cellAt(nx, y, nz) != AIR) {
					y++;
					continue;
				}
				int y0 = y;
				while (y < t && cellAt(nx, y, nz) == AIR) y++;
				float sun = LightBaker.sun(window, wx + 0.5f + d[0] * 0.9f, (y0 + y) * 0.5f, wz + 0.5f + d[1] * 0.9f);
				sideQuad(mb, ed, y0, y, sideColor, d[2] | flags, sun, sky * 0.8f, e, nx, nz);
			}
		}
	}

	/** Top face with per-corner voxel AO and the quad diagonal flipped to avoid AO seams. */
	private void topQuad(MeshBuilder mb, int wx, int t, int wz, float x0, float z0, float x1, float z1, int color, int code,
						 float sun, float sky, float emit) {
		int[][] corners = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
		float[] vx = {x0, x0, x1, x1};
		float[] vz = {z0, z1, z1, z0};
		float[] ao = new float[4];
		for (int k = 0; k < 4; k++) ao[k] = topAo(wx, t, wz, corners[k][0], corners[k][1]);
		int start = ao[0] + ao[2] < ao[1] + ao[3] ? 1 : 0;
		for (int q = 0; q < 4; q++) {
			int k = (q + start) & 3;
			mb.vertex(vx[k], t, vz[k], color, MeshBuilder.N_UP | code, sun, sky, ao[k], emit);
		}
	}

	/** A wall segment [y0, y1) facing a neighbour; contact shadow where it meets solid ground below. */
	private void sideQuad(MeshBuilder mb, float[] e, int y0, int y1, int color, int code, float sun, float sky, float emit, int nx, int nz) {
		float aoLow = opaque(nx, y0 - 1, nz) ? 0.6f : 0.92f;
		float aoHigh = opaque(nx, y1, nz) ? 0.75f : 1f;
		mb.vertex(e[0], y1, e[1], color, code, sun, sky, aoHigh, emit);
		mb.vertex(e[0], y0, e[1], color, code, sun, sky * 0.85f, aoLow, emit * 0.8f);
		mb.vertex(e[2], y0, e[3], color, code, sun, sky * 0.85f, aoLow, emit * 0.8f);
		mb.vertex(e[2], y1, e[3], color, code, sun, sky, aoHigh, emit);
	}

	/** A block with its real boxes (stairs, slab, carpet, torch, lantern, vine, bamboo, fence post…). */
	private void emitShape(MeshBuilder mb, String block, Material mat, int rgb, float var, int wx, int y, int wz,
						   float x0, float z0, HeightWindow window, float sky, float emit) {
		int color = base(mat, rgb, var * 0.3f);
		boolean glow = mat == Material.EMISSIVE || mat == Material.LAVA;
		float e = glow ? 1f : emit;
		float sun = LightBaker.sun(window, wx + 0.5f, y + 0.6f, wz + 0.5f);
		int code = mat.isBuilt() ? MeshBuilder.FLAG_BUILT : 0;
		int topTile = tiles.tile(block, true), sideTile = tiles.tile(block, false);
		for (float[] bx : shape(block)) {
			float ax = x0 + bx[0], ay = y + bx[1], az = z0 + bx[2], bxx = x0 + bx[3], by = y + bx[4], bz = z0 + bx[5];
			mb.texture(topTile, mat.ordinal());
			mb.vertex(ax, by, az, color, MeshBuilder.N_UP | code, sun, sky, 1f, e);
			mb.vertex(ax, by, bz, color, MeshBuilder.N_UP | code, sun, sky, 1f, e);
			mb.vertex(bxx, by, bz, color, MeshBuilder.N_UP | code, sun, sky, 1f, e);
			mb.vertex(bxx, by, az, color, MeshBuilder.N_UP | code, sun, sky, 1f, e);
			if (bx[1] > 0.01f || !opaque(wx, y - 1, wz)) {
				int under = Palette.shade(color, -0.12f);
				mb.vertex(ax, ay, az, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.85f, e);
				mb.vertex(bxx, ay, az, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.85f, e);
				mb.vertex(bxx, ay, bz, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.85f, e);
				mb.vertex(ax, ay, bz, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.85f, e);
			}
			mb.texture(sideTile, mat.ordinal());
			float lowAo = bx[1] < 0.01f && opaque(wx, y - 1, wz) ? 0.7f : 1f;
			for (int[] d : DIRS) {
				float[] ed = edge(d[2], ax, az, bxx, bz);
				mb.vertex(ed[0], by, ed[1], color, d[2] | code, sun, sky * 0.8f, 1f, e);
				mb.vertex(ed[0], ay, ed[1], color, d[2] | code, sun, sky * 0.8f, lowAo, e);
				mb.vertex(ed[2], ay, ed[3], color, d[2] | code, sun, sky * 0.8f, lowAo, e);
				mb.vertex(ed[2], by, ed[3], color, d[2] | code, sun, sky * 0.8f, 1f, e);
			}
		}
	}

	/** Water: a surface with shore coverage for pixel foam, sides only where the water ends in the air. */
	private void emitWater(MeshBuilder mb, ChunkColumns c, int i, int wx, int wz, float x0, float z0, float x1, float z1,
						   int t, int b, int rgb, HeightWindow window, float sky, float emit) {
		int water = Palette.harmonize(Material.WATER, rgb, 0f);
		int depth = Math.max(0, t - c.floor[i]);
		int step = Math.min(5, (depth + 1) / 2);
		int top = Palette.shade(water, 0.06f - step * 0.045f);
		if (cellAt(wx, t, wz) != WATER) {
			float sun = LightBaker.sun(window, wx + 0.5f, t + 0.05f, wz + 0.5f);
			int[][] corners = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
			float[] vx = {x0, x0, x1, x1};
			float[] vz = {z0, z1, z1, z0};
			mb.texture(0, Material.WATER.ordinal());
			for (int k = 0; k < 4; k++) {
				int sx = corners[k][0], sz = corners[k][1];
				int shore = (opaque(wx + sx, t - 1, wz) ? 1 : 0) + (opaque(wx, t - 1, wz + sz) ? 1 : 0) + (opaque(wx + sx, t - 1, wz + sz) ? 1 : 0);
				float open = shore == 0 ? 1f : (shore == 1 ? 0.45f : 0.15f);
				mb.vertex(vx[k], t, vz[k], top, MeshBuilder.N_UP | MeshBuilder.FLAG_WATER, sun, sky, open, emit);
			}
		}
		int side = Palette.shade(water, -0.10f);
		for (int[] d : DIRS) {
			int nx = wx + d[0], nz = wz + d[1];
			float[] ed = edge(d[2], x0, z0, x1, z1);
			int y = b;
			while (y < t) {
				if (cellAt(nx, y, nz) != AIR) {
					y++;
					continue;
				}
				int y0 = y;
				while (y < t && cellAt(nx, y, nz) == AIR) y++;
				sideQuad(mb, ed, y0, y, side, d[2] | MeshBuilder.FLAG_WATER, 0.8f, sky * 0.8f, emit, nx, nz);
			}
		}
	}

	/** The natural ground: textured top where nothing rests on it, strata walls only towards real air. */
	private void emitGround(MeshBuilder mb, ChunkColumns c, int i, int wx, int wz, float x0, float z0, float x1, float z1,
							HeightWindow window, float sky, float emit, float var) {
		int f = c.floor[i];
		Material gm = c.groundMaterial(i);
		String gBlock = c.floorName(i);
		int gColor = base(gm, c.floorColor[i], var * 0.6f);
		if (c.decoColor[i] != 0) gColor = Palette.mix(gColor, Palette.harmonize(Material.PLANT, c.decoColor[i], var), 0.3f);
		if (cellAt(wx, f, wz) == AIR) {
			float sun = LightBaker.sun(window, wx + 0.5f, f + 0.05f, wz + 0.5f);
			mb.texture(tiles.tile(gBlock, true), gm.ordinal());
			topQuad(mb, wx, f, wz, x0, z0, x1, z1, gColor, 0, sun, sky, emit);
		}
		Material sub = c.subMaterial(i) == Material.NONE ? gm.subsurface() : c.subMaterial(i);
		String subBlock = c.subName(i);
		int subColor = base(sub, c.subColor[i], var * 0.3f);
		int stone = base(Material.STONE, tiles.color(STONE, false) >= 0 ? tiles.color(STONE, false) : 0x7F7F7F, var * 0.3f);
		int surfSide = gm == Material.GRASS ? Palette.mix(base(Material.DIRT, tiles.color(gBlock, false) >= 0 ? tiles.color(gBlock, false) : 0x866043, var * 0.4f), gColor, 0.2f)
			: Palette.shade(gColor, -0.03f);
		for (int[] d : DIRS) {
			int nx = wx + d[0], nz = wz + d[1];
			ChunkColumns nc = nearby(nx >> 4, nz >> 4);
			if (nc == null) continue;
			int ni = ChunkColumns.index(nx & 15, nz & 15);
			if (!nc.isKnown(ni)) continue;
			int lowest = nc.floor[ni];
			if (lowest >= f) continue;
			float[] ed = edge(d[2], x0, z0, x1, z1);
			int y = f - 1;
			while (y >= lowest) {
				if (cellAt(nx, y, nz) != AIR) {
					y--;
					continue;
				}
				int yTop = y + 1;
				int band = bandOf(f - 1 - y);
				while (y >= lowest && cellAt(nx, y, nz) == AIR && bandOf(f - 1 - y) == band) y--;
				int y0 = y + 1;
				float sun = LightBaker.sun(window, wx + 0.5f + d[0] * 0.9f, (y0 + yTop) * 0.5f, wz + 0.5f + d[1] * 0.9f);
				switch (band) {
					case 0 -> {
						mb.texture(tiles.tile(gBlock, false), gm.ordinal());
						sideQuad(mb, ed, y0, yTop, surfSide, d[2], sun, sky * 0.8f, emit, nx, nz);
					}
					case 1 -> {
						mb.texture(tiles.tile(subBlock, false), sub.ordinal());
						sideQuad(mb, ed, y0, yTop, subColor, d[2], sun, sky * 0.75f, emit, nx, nz);
					}
					default -> {
						mb.texture(tiles.tile(STONE, false), Material.STONE.ordinal());
						sideQuad(mb, ed, y0, yTop, stone, d[2], sun, sky * 0.7f, emit, nx, nz);
					}
				}
			}
		}
	}

	private static int bandOf(int depthBelowSurface) {
		return depthBelowSurface <= 0 ? 0 : (depthBelowSurface <= 3 ? 1 : 2);
	}

	// ------------------------------------------------------------------ coarse levels

	/** Representative column of a coarse cell: its visible top and the real ground under it. */
	private record Cell(int top, Material mat, int color, String block, int wx, int wz,
						int ground, Material groundMat, int groundColor, String groundBlock) {
		boolean known() {
			return top != ChunkColumns.UNKNOWN;
		}

		/** Vegetation standing well above the ground: drawn as a floating slab, never a pillar. */
		boolean canopy() {
			return mat == Material.FOLIAGE && top - ground > 2;
		}
	}

	private static final Cell UNKNOWN_CELL = new Cell(ChunkColumns.UNKNOWN, Material.NONE, 0, "", 0, 0, 0, Material.NONE, 0, "");

	private Cell cell(int cellX, int cellZ, int cellSize) {
		int x0 = cellX * cellSize, z0 = cellZ * cellSize;
		ChunkColumns c = nearby(x0 >> 4, z0 >> 4);
		if (c == null) return UNKNOWN_CELL;
		int best = -1, bestTop = Integer.MIN_VALUE;
		boolean bestBuilt = false;
		// Water wins the cell when it covers at least half of it: otherwise rivers and lakes
		// break into holes at distance (the banks are taller than the water surface).
		int known = 0, water = 0;
		int groundSum = 0;
		for (int dz = 0; dz < cellSize; dz++) {
			for (int dx = 0; dx < cellSize; dx++) {
				int i = ChunkColumns.index((x0 + dx) & 15, (z0 + dz) & 15);
				if (!c.isKnown(i)) continue;
				known++;
				groundSum += c.floor[i];
				if (c.material(i) == Material.WATER) water++;
			}
		}
		if (known == 0) return UNKNOWN_CELL;
		boolean waterCell = water * 2 >= known;
		for (int dz = 0; dz < cellSize; dz++) {
			for (int dx = 0; dx < cellSize; dx++) {
				int i = ChunkColumns.index((x0 + dx) & 15, (z0 + dz) & 15);
				if (!c.isKnown(i)) continue;
				if (waterCell != (c.material(i) == Material.WATER)) continue;
				boolean built = c.material(i).isBuilt();
				int t = c.top[i];
				if (best < 0 || (built && !bestBuilt) || (built == bestBuilt && t > bestTop)) {
					best = i;
					bestTop = t;
					bestBuilt = built;
				}
			}
		}
		if (best < 0) return UNKNOWN_CELL;
		int ground = Math.round(groundSum / (float) known);
		return new Cell(c.top[best], c.material(best), c.color[best], c.topName(best), (c.chunkX << 4) + (best & 15), (c.chunkZ << 4) + (best >> 4),
			ground, c.groundMaterial(best), c.floorColor[best], c.floorName(best));
	}

	private MeshData buildCoarse(int chunkX, int chunkZ, int lod, int anchorX, int anchorZ, HeightWindow window) {
		int cellSize = 1 << lod;
		int n = 16 / cellSize;
		int base = n + 2;
		Cell[] cells = new Cell[base * base];
		int cx0 = (chunkX << 4) / cellSize - 1;
		int cz0 = (chunkZ << 4) / cellSize - 1;
		for (int z = 0; z < base; z++) {
			for (int x = 0; x < base; x++) cells[z * base + x] = cell(cx0 + x, cz0 + z, cellSize);
		}
		MeshBuilder mb = new MeshBuilder(n * n * 3);
		for (int z = 1; z <= n; z++) {
			for (int x = 1; x <= n; x++) {
				Cell c = cells[z * base + x];
				if (!c.known()) continue;
				float x0 = (cx0 + x) * cellSize - anchorX, z0 = (cz0 + z) * cellSize - anchorZ;
				float x1 = x0 + cellSize, z1 = z0 + cellSize;
				float px = (cx0 + x) * cellSize + cellSize * 0.5f, pz = (cz0 + z) * cellSize + cellSize * 0.5f;
				float var = Palette.variation(c.wx, c.wz);
				boolean water = c.mat == Material.WATER;
				int color = water ? Palette.shade(Palette.harmonize(Material.WATER, c.color, 0f), -0.04f) : base(c.mat, c.color, var * 0.5f);
				float sun = LightBaker.sun(window, px, c.top + 0.05f, pz);
				float sky = LightBaker.sky(window, px, c.top, pz);
				float emit = LightBaker.emission(window, (int) px, (int) pz, c.top);
				int code = water ? MeshBuilder.FLAG_WATER : (c.mat.isBuilt() ? MeshBuilder.FLAG_BUILT : 0);
				mb.texture(water ? 0 : tiles.tile(c.block, true), c.mat.ordinal());
				coarseTop(mb, x0, z0, x1, z1, c.top, color, code, sun, sky, emit);
				mb.texture(water ? 0 : tiles.tile(c.block, false), c.mat.ordinal());
				int side = Palette.shade(color, -0.05f);
				if (c.canopy()) {
					// Canopy slab: sides only as thick as the leaves, an underside, and the ground below.
					int slabBottom = Math.max(c.ground + 1, c.top - Math.max(2, cellSize));
					for (int[] d : DIRS) {
						Cell nb = cells[(z + d[1]) * base + x + d[0]];
						if (nb.known() && nb.top >= c.top) continue;
						int bottom = nb.known() && !nb.canopy() ? Math.max(slabBottom, nb.top) : slabBottom;
						if (bottom >= c.top) continue;
						coarseSide(mb, d, x0, z0, x1, z1, c.top, bottom, side, code, sun, sky, emit);
					}
					int under = Palette.shade(color, -0.16f);
					mb.vertex(x0, slabBottom, z0, under, MeshBuilder.N_DOWN, 0f, sky * 0.5f, 0.8f, emit);
					mb.vertex(x1, slabBottom, z0, under, MeshBuilder.N_DOWN, 0f, sky * 0.5f, 0.8f, emit);
					mb.vertex(x1, slabBottom, z1, under, MeshBuilder.N_DOWN, 0f, sky * 0.5f, 0.8f, emit);
					mb.vertex(x0, slabBottom, z1, under, MeshBuilder.N_DOWN, 0f, sky * 0.5f, 0.8f, emit);
					int gColor = base(c.groundMat, c.groundColor, var * 0.5f);
					mb.texture(tiles.tile(c.groundBlock, true), c.groundMat.ordinal());
					coarseTop(mb, x0, z0, x1, z1, c.ground, gColor, 0, sun * 0.5f, sky * 0.6f, emit);
					continue;
				}
				for (int[] d : DIRS) {
					Cell nb = cells[(z + d[1]) * base + x + d[0]];
					if (!nb.known()) continue;
					int nTop = nb.canopy() ? nb.ground : nb.top;
					if (nTop >= c.top) continue;
					float sunW = LightBaker.sun(window, px + d[0] * (cellSize * 0.5f + 0.6f), (c.top + nTop) * 0.5f, pz + d[1] * (cellSize * 0.5f + 0.6f));
					coarseSide(mb, d, x0, z0, x1, z1, c.top, nTop, side, code, sunW, sky, emit);
				}
			}
		}
		return MeshData.of(mb);
	}

	/** What a coarse cell (s×s×s blocks) holds: opaque and water block counts, opaque extent, water surface. */
	private record CStat(int count, int water, int top, int bottom, int waterTop) {
	}

	private CStat coarseStat(int gx, int gy, int gz, int s, Map<Long, CStat> cache) {
		long key = ((long) (gx & 0x1FFFFF) << 42) | ((long) (gy & 0x1FFFFF) << 21) | (gz & 0x1FFFFF);
		CStat hit = cache.get(key);
		if (hit != null) return hit;
		int count = 0, water = 0, top = Integer.MIN_VALUE, bottom = Integer.MAX_VALUE, waterTop = Integer.MIN_VALUE;
		int x0 = gx * s, y0 = gy * s, z0 = gz * s;
		for (int y = y0; y < y0 + s; y++) {
			for (int z = z0; z < z0 + s; z++) {
				for (int x = x0; x < x0 + s; x++) {
					int k = voxelAt(x, y, z);
					if (k == V_OPAQUE) {
						count++;
						top = Math.max(top, y + 1);
						bottom = Math.min(bottom, y);
					} else if (k == V_WATER) {
						water++;
						waterTop = Math.max(waterTop, y + 1);
					}
				}
			}
		}
		CStat st = new CStat(count, water, top, bottom, waterTop);
		cache.put(key, st);
		return st;
	}

	/**
	 * Far levels of detail built from the voxel grid instead of a heightfield: blocks are
	 * merged into s×s×s cells (2, 4 or 8) and only faces between a full cell and an open one
	 * are drawn, exactly like the near detail. Floating islands, arches, Nether ceilings and
	 * overhangs stay what they are at any distance instead of turning into pillars.
	 * A cell counts as full when it holds at least one whole layer of blocks, so one-block
	 * floors and roofs survive; its faces sit at the real top/bottom of its blocks, so the
	 * coarse terrain keeps its true heights instead of snapping to the cell grid.
	 */
	private MeshData buildVoxelCoarse(int chunkX, int chunkZ, int lod, int anchorX, int anchorZ, HeightWindow window) {
		ChunkColumns c = nearby(chunkX, chunkZ);
		ChunkVoxels v = c.voxels;
		int s = 1 << lod, n = 16 / s, need = s * s;
		int gx0 = (chunkX << 4) / s, gz0 = (chunkZ << 4) / s;
		int gy0 = Math.floorDiv(v.yMin, s), gy1 = Math.floorDiv(v.yMax() - 1, s);
		Map<Long, CStat> cache = new HashMap<>();
		MeshBuilder mb = new MeshBuilder(n * n * 12);
		for (int gy = gy0; gy <= gy1; gy++) {
			for (int gz = 0; gz < n; gz++) {
				for (int gx = 0; gx < n; gx++) {
					int cgx = gx0 + gx, cgz = gz0 + gz;
					CStat a = coarseStat(cgx, gy, cgz, s, cache);
					CStat up = coarseStat(cgx, gy + 1, cgz, s, cache);
					float x0 = cgx * s - anchorX, z0 = cgz * s - anchorZ, x1 = x0 + s, z1 = z0 + s;
					float px = cgx * s + s * 0.5f, pz = cgz * s + s * 0.5f;
					if (a.count < need) {
						// Open water surface.
						if (a.water >= need && up.water < need && up.count < need) {
							int lx = gx * s + s / 2, lz = gz * s + s / 2;
							int wt = v.waterTint[(lz << 4) | lx];
							int color = Palette.shade(Palette.harmonize(Material.WATER, wt != 0 ? wt & 0xFFFFFF : 0x3F76E4, 0f), -0.04f);
							float sky = LightBaker.sky(window, px, a.waterTop, pz);
							mb.texture(0, Material.WATER.ordinal());
							coarseTop(mb, x0, z0, x1, z1, a.waterTop, color, MeshBuilder.FLAG_WATER,
								LightBaker.sun(window, px, a.waterTop + 0.05f, pz), sky, LightBaker.emission(window, (int) px, (int) pz, a.waterTop));
						}
						continue;
					}
					CStat dn = coarseStat(cgx, gy - 1, cgz, s, cache);
					int top = up.count >= need ? (gy + 1) * s : a.top;
					int bottom = dn.count >= need ? gy * s : a.bottom;

					// Look: the visible (topmost) block of every sub-column, averaged.
					int r = 0, g = 0, b = 0, samples = 0, bestY = Integer.MIN_VALUE;
					ChunkVoxels.Entry best = null;
					for (int sz = 0; sz < s; sz++) {
						for (int sx = 0; sx < s; sx++) {
							int lx = gx * s + sx, lz = gz * s + sz;
							for (int y = top - 1; y >= gy * s; y--) {
								ChunkVoxels.Entry e = v.entryAt(lx, y, lz);
								if (e == null || e.material() == Material.WATER) continue;
								int col = base(e.material(), v.sourceColor(e, lx, lz), Palette.variation((chunkX << 4) + lx, (chunkZ << 4) + lz) * 0.5f);
								r += (col >> 16) & 255;
								g += (col >> 8) & 255;
								b += col & 255;
								samples++;
								if (y > bestY) {
									bestY = y;
									best = e;
								}
								break;
							}
						}
					}
					Material mat = best != null ? best.material() : Material.STONE;
					String block = best != null ? best.key() : STONE;
					int color = samples > 0 ? ((r / samples) << 16) | ((g / samples) << 8) | (b / samples)
						: base(Material.STONE, tiles.color(STONE, false) >= 0 ? tiles.color(STONE, false) : 0x7F7F7F, 0f);
					int code = mat.isBuilt() ? MeshBuilder.FLAG_BUILT : 0;
					float sky = LightBaker.sky(window, px, top, pz);
					float emit = LightBaker.emission(window, (int) px, (int) pz, top);
					if (up.count < need) {
						mb.texture(tiles.tile(block, true), mat.ordinal());
						coarseTop(mb, x0, z0, x1, z1, top, color, code, LightBaker.sun(window, px, top + 0.05f, pz), sky, emit);
					}
					int side = Palette.shade(color, -0.05f);
					mb.texture(tiles.tile(block, false), mat.ordinal());
					if (dn.count < need && dn.count + dn.water < s * s * s) {
						int under = Palette.shade(color, -0.16f);
						mb.vertex(x0, bottom, z0, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.8f, emit);
						mb.vertex(x1, bottom, z0, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.8f, emit);
						mb.vertex(x1, bottom, z1, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.8f, emit);
						mb.vertex(x0, bottom, z1, under, MeshBuilder.N_DOWN | code, 0f, sky * 0.5f, 0.8f, emit);
					}
					for (int[] d : DIRS) {
						CStat nb = coarseStat(cgx + d[0], gy, cgz + d[1], s, cache);
						float sunW = LightBaker.sun(window, px + d[0] * (s * 0.5f + 0.6f), (top + bottom) * 0.5f, pz + d[1] * (s * 0.5f + 0.6f));
						if (nb.count < need) {
							coarseSide(mb, d, x0, z0, x1, z1, top, bottom, side, code, sunW, sky, emit);
							continue;
						}
						// Both full: close the steps where their real heights differ.
						CStat nUp = coarseStat(cgx + d[0], gy + 1, cgz + d[1], s, cache);
						CStat nDn = coarseStat(cgx + d[0], gy - 1, cgz + d[1], s, cache);
						int nTop = nUp.count >= need ? (gy + 1) * s : nb.top;
						int nBottom = nDn.count >= need ? gy * s : nb.bottom;
						if (nTop < top) coarseSide(mb, d, x0, z0, x1, z1, top, nTop, side, code, sunW, sky, emit);
						if (nBottom > bottom) coarseSide(mb, d, x0, z0, x1, z1, nBottom, bottom, side, code, sunW, sky, emit);
					}
				}
			}
		}
		return MeshData.of(mb);
	}

	private static void coarseTop(MeshBuilder mb, float x0, float z0, float x1, float z1, int y, int color, int code,
								  float sun, float sky, float emit) {
		mb.vertex(x0, y, z0, color, MeshBuilder.N_UP | code, sun, sky, 1f, emit);
		mb.vertex(x0, y, z1, color, MeshBuilder.N_UP | code, sun, sky, 1f, emit);
		mb.vertex(x1, y, z1, color, MeshBuilder.N_UP | code, sun, sky, 1f, emit);
		mb.vertex(x1, y, z0, color, MeshBuilder.N_UP | code, sun, sky, 1f, emit);
	}

	private static void coarseSide(MeshBuilder mb, int[] d, float x0, float z0, float x1, float z1, int top, int bottom, int color, int code,
								   float sun, float sky, float emit) {
		float[] ed = edge(d[2], x0, z0, x1, z1);
		mb.vertex(ed[0], top, ed[1], color, d[2] | code, sun, sky * 0.8f, 1f, emit);
		mb.vertex(ed[0], bottom, ed[1], color, d[2] | code, sun, sky * 0.7f, 0.7f, emit);
		mb.vertex(ed[2], bottom, ed[3], color, d[2] | code, sun, sky * 0.7f, 0.7f, emit);
		mb.vertex(ed[2], top, ed[3], color, d[2] | code, sun, sky * 0.8f, 1f, emit);
	}

	/** Block light at a vertex corner: the average of the four columns that share it. */
	private static float cornerEmit(float[] grid, int cornerX, int cornerZ) {
		float s = 0f;
		for (int dz = -1; dz <= 0; dz++)
			for (int dx = -1; dx <= 0; dx++)
				s += grid[(cornerZ + dz + 1) * 18 + (cornerX + dx + 1)];
		return s * 0.25f;
	}
}
