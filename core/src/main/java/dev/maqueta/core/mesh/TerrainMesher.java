package dev.maqueta.core.mesh;

import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;
import dev.maqueta.core.Material;
import dev.maqueta.core.Palette;
import dev.maqueta.core.light.HeightWindow;
import dev.maqueta.core.light.LightBaker;

/**
 * Turns explored columns into the maqueta surface: flat-coloured column tops, strata walls
 * on cliffs and on the edge of the explored area (the "cut-out" look of a diorama), water
 * coloured by depth with shore foam, and four baked light terms per vertex.
 * <p>
 * Level of detail {@code lod} merges {@code 2^lod} columns per cell. The tallest column of a
 * cell wins, so buildings never disappear before the natural terrain does.
 */
public final class TerrainMesher {
	private static final int[][] DIRS = {{0, -1, MeshBuilder.N_NORTH}, {0, 1, MeshBuilder.N_SOUTH}, {-1, 0, MeshBuilder.N_WEST}, {1, 0, MeshBuilder.N_EAST}};

	private final ColumnStore store;
	private final int boardY;

	public TerrainMesher(ColumnStore store, int boardY) {
		this.store = store;
		this.boardY = boardY;
	}

	/** Representative column of a cell. */
	private record Cell(int top, int floor, Material mat, int color, Material sub, int subColor, int floorColor, int deco, int wx, int wz) {
		boolean known() {
			return top != ChunkColumns.UNKNOWN;
		}

		/** A tree: canopy floating above a lower ground. */
		boolean canopy() {
			return known() && mat != Material.WATER && floor != ChunkColumns.UNKNOWN && floor < top - 1;
		}

		/** Height of the walkable ground (under the canopy for trees). */
		int ground() {
			return canopy() ? floor : top;
		}
	}

	private static final Cell UNKNOWN_CELL = new Cell(ChunkColumns.UNKNOWN, ChunkColumns.UNKNOWN, Material.NONE, 0, Material.NONE, 0, 0, 0, 0, 0);

	private Cell cell(int cellX, int cellZ, int cellSize) {
		int x0 = cellX * cellSize, z0 = cellZ * cellSize;
		ChunkColumns c = store.get(x0 >> 4, z0 >> 4);
		if (c == null) return UNKNOWN_CELL;
		int best = -1;
		int bestTop = Integer.MIN_VALUE;
		boolean bestBuilt = false;
		for (int dz = 0; dz < cellSize; dz++) {
			for (int dx = 0; dx < cellSize; dx++) {
				int i = ChunkColumns.index((x0 + dx) & 15, (z0 + dz) & 15);
				if (!c.isKnown(i)) continue;
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
		int wx = (c.chunkX << 4) + (best & 15);
		int wz = (c.chunkZ << 4) + (best >> 4);
		return new Cell(c.top[best], c.floor[best], c.material(best), c.color[best], c.subMaterial(best), c.subColor[best],
			c.floorColor[best], c.decoColor[best], wx, wz);
	}

	public MeshData build(int chunkX, int chunkZ, int lod, int anchorX, int anchorZ) {
		ChunkColumns self = store.get(chunkX, chunkZ);
		if (self == null) return MeshData.EMPTY;
		int cellSize = 1 << lod;
		int n = 16 / cellSize;
		int base = n + 2;

		// Cells for the chunk plus a one-cell ring of neighbours.
		Cell[] cells = new Cell[base * base];
		int cx0 = (chunkX << 4) / cellSize - 1;
		int cz0 = (chunkZ << 4) / cellSize - 1;
		for (int z = 0; z < base; z++) {
			for (int x = 0; x < base; x++) {
				cells[z * base + x] = cell(cx0 + x, cz0 + z, cellSize);
			}
		}

		HeightWindow window = new HeightWindow(store, chunkX, chunkZ);
		float[] sun = new float[base * base];
		float[] sky = new float[base * base];
		float[] emit = new float[base * base];
		for (int z = 0; z < base; z++) {
			for (int x = 0; x < base; x++) {
				Cell c = cells[z * base + x];
				if (!c.known()) {
					sun[z * base + x] = 1f;
					sky[z * base + x] = 1f;
					continue;
				}
				float px = (cx0 + x) * cellSize + cellSize * 0.5f;
				float pz = (cz0 + z) * cellSize + cellSize * 0.5f;
				sun[z * base + x] = LightBaker.sun(window, px, c.top + 0.05f, pz);
				sky[z * base + x] = LightBaker.sky(window, px, c.top, pz);
				emit[z * base + x] = LightBaker.emission(window, (int) Math.floor(px), (int) Math.floor(pz), c.top);
			}
		}

		MeshBuilder mb = new MeshBuilder(n * n * 3);
		for (int z = 1; z <= n; z++) {
			for (int x = 1; x <= n; x++) {
				Cell c = cells[z * base + x];
				if (!c.known()) continue;
				float x0 = (cx0 + x) * cellSize - anchorX;
				float z0 = (cz0 + z) * cellSize - anchorZ;
				float x1 = x0 + cellSize, z1 = z0 + cellSize;
				int h = c.top;
				boolean water = c.mat == Material.WATER;
				boolean lava = c.mat == Material.LAVA;
				int topColor = topColor(c);
				int flags = (water ? MeshBuilder.FLAG_WATER : 0) | (c.mat.isBuilt() ? MeshBuilder.FLAG_BUILT : 0);

				// --- top face: per-vertex AO, soft light averaged over the plateau ---
				int[][] corners = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
				float[] vx = {x0, x0, x1, x1};
				float[] vz = {z0, z1, z1, z0};
				float[] ao = new float[4], vs = new float[4], vk = new float[4], ve = new float[4];
				int[] col = new int[4];
				for (int k = 0; k < 4; k++) {
					int sx = corners[k][0], sz = corners[k][1];
					Cell s1 = cells[z * base + x + sx];
					Cell s2 = cells[(z + sz) * base + x];
					Cell cc = cells[(z + sz) * base + x + sx];
					ao[k] = water ? 1f : LightBaker.vertexAo(higher(s1, h, c.canopy()), higher(s2, h, c.canopy()), higher(cc, h, c.canopy()));
					vs[k] = avgLight(sun, cells, base, x, z, sx, sz, h);
					vk[k] = avgLight(sky, cells, base, x, z, sx, sz, h);
					ve[k] = Math.max(emit[z * base + x], (emit[z * base + x + sx] + emit[(z + sz) * base + x]) * 0.5f);
					col[k] = topColor;
					if (water && (isShore(s1, h) || isShore(s2, h) || isShore(cc, h))) {
						col[k] = Palette.mix(topColor, 0xE6F4F8, 0.55f);
					}
				}
				if (lava) {
					for (int k = 0; k < 4; k++) ve[k] = 1f;
				}
				// Flip the quad diagonal so AO gradients interpolate without seams.
				int start = ao[0] + ao[2] < ao[1] + ao[3] ? 1 : 0;
				for (int q = 0; q < 4; q++) {
					int k = (q + start) & 3;
					mb.vertex(vx[k], h, vz[k], col[k], MeshBuilder.N_UP | flags, vs[k], vk[k], ao[k], ve[k]);
				}

				float cxw = (cx0 + x) * cellSize + cellSize * 0.5f, czw = (cz0 + z) * cellSize + cellSize * 0.5f;
				if (c.canopy()) {
					emitCanopy(mb, c, cells, base, x, z, x0, z0, x1, z1, window, cxw, czw, sky[z * base + x], emit[z * base + x]);
					continue;
				}

				// --- side walls (against the neighbour's ground, so walls run under tree canopies too) ---
				for (int[] d : DIRS) {
					Cell nb = cells[(z + d[1]) * base + x + d[0]];
					int nTop = nb.known() ? nb.ground() : boardY;
					if (!nb.known() && h <= boardY) continue;
					if (nTop >= h) continue;
					float sunW = LightBaker.sun(window, (cx0 + x) * cellSize + cellSize * 0.5f + d[0] * (cellSize * 0.5f + 0.6f),
						(h + nTop) * 0.5f, (cz0 + z) * cellSize + cellSize * 0.5f + d[1] * (cellSize * 0.5f + 0.6f));
					float skyW = sky[z * base + x] * 0.75f;
					float e = emit[z * base + x];
					boolean groundKnown = nb.known();
					emitWall(mb, c, d, x0, z0, x1, z1, h, nTop, groundKnown, sunW, skyW, e, flags & MeshBuilder.FLAG_BUILT);
				}
			}
		}
		return MeshData.of(mb);
	}

	private static boolean higher(Cell c, int h) {
		return c.known() && c.top > h;
	}

	private static boolean higher(Cell c, int h, boolean selfCanopy) {
		return c.known() && (selfCanopy ? c.top : c.ground()) > h;
	}

	/**
	 * A tree cell: the canopy is a clean floating volume (design 3.2) above the real ground,
	 * which is meshed separately so walking paths under forests stay readable.
	 */
	private void emitCanopy(MeshBuilder mb, Cell c, Cell[] cells, int base, int x, int z, float x0, float z0, float x1, float z1,
							HeightWindow window, float cxw, float czw, float skyCell, float emitCell) {
		int h = c.top;
		int depth = Math.max(2, Math.min(4, Math.round((c.top - c.floor) * 0.6f)));
		int slabBottom = Math.max(c.floor + 1, h - depth);
		float var = Palette.variation(c.wx, c.wz);
		int leaf = Palette.shade(Palette.stylize(c.mat, c.color, var * 0.6f), -0.04f);

		// Canopy sides: only where the neighbour does not already cover them.
		for (int[] d : DIRS) {
			Cell nb = cells[(z + d[1]) * base + x + d[0]];
			if (nb.known() && nb.top >= h) continue;
			int bottom = nb.known() ? Math.max(slabBottom, Math.min(nb.top, h)) : slabBottom;
			if (nb.known() && nb.canopy()) bottom = Math.max(slabBottom, nb.top);
			if (bottom >= h) continue;
			float sunW = LightBaker.sun(window, cxw + d[0] * 0.9f, (h + bottom) * 0.5f, czw + d[1] * 0.9f);
			float ax, az, bx, bz;
			switch (d[2]) {
				case MeshBuilder.N_NORTH -> { ax = x0; az = z0; bx = x1; bz = z0; }
				case MeshBuilder.N_SOUTH -> { ax = x1; az = z1; bx = x0; bz = z1; }
				case MeshBuilder.N_WEST -> { ax = x0; az = z1; bx = x0; bz = z0; }
				default -> { ax = x1; az = z0; bx = x1; bz = z1; }
			}
			band(mb, ax, az, bx, bz, bottom, h, leaf, d[2], sunW, skyCell * 0.8f, emitCell, bottom - 3, true);
		}
		// Underside, in the canopy's own shade.
		int under = Palette.shade(leaf, -0.14f);
		mb.vertex(x0, slabBottom, z0, under, MeshBuilder.N_DOWN, 0f, 0.35f, 0.8f, emitCell);
		mb.vertex(x1, slabBottom, z0, under, MeshBuilder.N_DOWN, 0f, 0.35f, 0.8f, emitCell);
		mb.vertex(x1, slabBottom, z1, under, MeshBuilder.N_DOWN, 0f, 0.35f, 0.8f, emitCell);
		mb.vertex(x0, slabBottom, z1, under, MeshBuilder.N_DOWN, 0f, 0.35f, 0.8f, emitCell);

		// Ground under the tree.
		Material groundMat = c.sub == Material.NONE ? Material.GRASS : c.sub;
		Cell ground = new Cell(c.floor, c.floor, groundMat, c.floorColor, groundMat.subsurface(), 0, c.floorColor, 0, c.wx, c.wz);
		int gy = c.floor;
		int groundColor = groundMat == Material.WATER ? Palette.stylize(Material.WATER, c.floorColor, 0f)
			: Palette.stylize(groundMat, c.floorColor, var * 0.6f);
		float gsun = LightBaker.sun(window, cxw, gy + 0.05f, czw);
		int gflags = groundMat == Material.WATER ? MeshBuilder.FLAG_WATER : 0;
		mb.vertex(x0, gy, z0, groundColor, MeshBuilder.N_UP | gflags, gsun, skyCell * 0.5f, 0.78f, emitCell);
		mb.vertex(x0, gy, z1, groundColor, MeshBuilder.N_UP | gflags, gsun, skyCell * 0.5f, 0.78f, emitCell);
		mb.vertex(x1, gy, z1, groundColor, MeshBuilder.N_UP | gflags, gsun, skyCell * 0.5f, 0.78f, emitCell);
		mb.vertex(x1, gy, z0, groundColor, MeshBuilder.N_UP | gflags, gsun, skyCell * 0.5f, 0.78f, emitCell);
		for (int[] d : DIRS) {
			Cell nb = cells[(z + d[1]) * base + x + d[0]];
			int nGround = nb.known() ? nb.ground() : boardY;
			if (!nb.known() && gy <= boardY) continue;
			if (nGround >= gy) continue;
			float sunW = LightBaker.sun(window, cxw + d[0] * 0.9f, (gy + nGround) * 0.5f, czw + d[1] * 0.9f);
			emitWall(mb, ground, d, x0, z0, x1, z1, gy, nGround, nb.known(), sunW, skyCell * 0.5f, emitCell, 0);
		}
	}

	private static boolean isShore(Cell c, int h) {
		return c.known() && c.mat != Material.WATER && c.top >= h;
	}

	private static float avgLight(float[] term, Cell[] cells, int base, int x, int z, int sx, int sz, int h) {
		float sum = term[z * base + x] * 2f;
		float w = 2f;
		int[][] nb = {{x + sx, z}, {x, z + sz}, {x + sx, z + sz}};
		for (int[] p : nb) {
			Cell c = cells[p[1] * base + p[0]];
			if (c.known() && Math.abs(c.top - h) <= 1) {
				sum += term[p[1] * base + p[0]];
				w += 1f;
			}
		}
		return sum / w;
	}

	private int topColor(Cell c) {
		float var = Palette.variation(c.wx, c.wz);
		if (c.mat == Material.WATER) {
			int water = Palette.stylize(Material.WATER, c.color, 0f);
			int depth = Math.max(0, c.top - c.floor);
			water = Palette.shade(water, -Math.min(depth, 14) * 0.022f + 0.06f);
			return water;
		}
		int base = Palette.stylize(c.mat, c.color, c.mat.isBuilt() ? var * 0.25f : var * 0.6f);
		if (c.deco != 0) {
			base = Palette.mix(base, Palette.stylize(Material.PLANT, c.deco, var), 0.35f);
		}
		return base;
	}

	/**
	 * A wall from {@code h} down to {@code bottom}, split in strata bands:
	 * 1 block of surface material, 3 of subsurface, the rest stone. Water columns show
	 * the water layer first, then the seabed strata.
	 */
	private void emitWall(MeshBuilder mb, Cell c, int[] d, float x0, float z0, float x1, float z1, int h, int bottom,
						  boolean groundKnown, float sun, float sky, float emit, int flags) {
		float ax, az, bx, bz;
		switch (d[2]) {
			case MeshBuilder.N_NORTH -> { ax = x0; az = z0; bx = x1; bz = z0; }
			case MeshBuilder.N_SOUTH -> { ax = x1; az = z1; bx = x0; bz = z1; }
			case MeshBuilder.N_WEST -> { ax = x0; az = z1; bx = x0; bz = z0; }
			default -> { ax = x1; az = z0; bx = x1; bz = z1; }
		}
		float var = Palette.variation(c.wx, c.wz);
		int y = h;
		if (c.mat == Material.WATER) {
			int waterSide = Palette.shade(Palette.stylize(Material.WATER, c.color, 0f), -0.08f);
			int fl = Math.max(bottom, c.floor);
			if (fl < y) {
				band(mb, ax, az, bx, bz, fl, y, waterSide, d[2] | MeshBuilder.FLAG_WATER, sun, sky, emit, bottom, groundKnown);
				y = fl;
			}
			if (y <= bottom) return;
			int seabed = Palette.stylize(Material.SAND, c.floorColor, var);
			int seaTop = Math.max(bottom, y - 1);
			band(mb, ax, az, bx, bz, seaTop, y, seabed, d[2], sun, sky, emit, bottom, groundKnown);
			y = seaTop;
		} else {
			int surf = Palette.shade(Palette.stylize(c.mat, c.color, var * 0.4f), -0.03f);
			int bandTop = c.mat.isBuilt() ? bottom : Math.max(bottom, y - 1);
			band(mb, ax, az, bx, bz, bandTop, y, surf, d[2] | flags, sun, sky, emit, bottom, groundKnown);
			y = bandTop;
		}
		if (y <= bottom) return;
		Material subMat = c.sub == Material.NONE ? c.mat.subsurface() : c.sub;
		int sub = Palette.stylize(subMat, c.subColor, var * 0.3f);
		int subBottom = Math.max(bottom, y - 3);
		band(mb, ax, az, bx, bz, subBottom, y, sub, d[2], sun, sky, emit, bottom, groundKnown);
		y = subBottom;
		if (y <= bottom) return;
		int stone = Palette.stylize(Material.STONE, 0, var * 0.3f);
		band(mb, ax, az, bx, bz, bottom, y, stone, d[2], sun, sky, emit, bottom, groundKnown);
	}

	private static void band(MeshBuilder mb, float ax, float az, float bx, float bz, int y0, int y1, int rgb, int code,
							 float sun, float sky, float emit, int wallBottom, boolean groundKnown) {
		float ao0 = wallAo(y0, wallBottom, groundKnown);
		float ao1 = wallAo(y1, wallBottom, groundKnown);
		float sky0 = sky * (0.55f + 0.45f * ao0);
		float sky1 = sky * (0.55f + 0.45f * ao1);
		mb.vertex(ax, y1, az, rgb, code, sun, sky1, ao1, emit);
		mb.vertex(ax, y0, az, rgb, code, sun, sky0, ao0, emit * 0.7f);
		mb.vertex(bx, y0, bz, rgb, code, sun, sky0, ao0, emit * 0.7f);
		mb.vertex(bx, y1, bz, rgb, code, sun, sky1, ao1, emit);
	}

	private static float wallAo(int y, int bottom, boolean groundKnown) {
		if (!groundKnown) return 0.92f;
		float t = Math.min(1f, (y - bottom) / 3f);
		return 0.52f + 0.48f * t;
	}
}
