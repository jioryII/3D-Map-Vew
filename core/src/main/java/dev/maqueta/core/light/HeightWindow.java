package dev.maqueta.core.light;

import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;

/**
 * A dense copy of surface heights and emission around one chunk, so light baking does
 * array reads instead of thousands of hash lookups. Unknown columns are marked so the
 * baker can treat them as open sky (no phantom shadows from unexplored land).
 */
public final class HeightWindow {
	public static final int MARGIN = 48;

	public final int originX;
	public final int originZ;
	public final int size;
	private final short[] top;
	private final byte[] emission;

	public HeightWindow(ColumnStore store, int chunkX, int chunkZ) {
		this.originX = (chunkX << 4) - MARGIN;
		this.originZ = (chunkZ << 4) - MARGIN;
		this.size = 16 + MARGIN * 2;
		this.top = new short[size * size];
		this.emission = new byte[size * size];
		java.util.Arrays.fill(top, ChunkColumns.UNKNOWN);

		int c0x = originX >> 4, c1x = (originX + size - 1) >> 4;
		int c0z = originZ >> 4, c1z = (originZ + size - 1) >> 4;
		for (int cz = c0z; cz <= c1z; cz++) {
			for (int cx = c0x; cx <= c1x; cx++) {
				ChunkColumns c = store.get(cx, cz);
				if (c == null) continue;
				for (int lz = 0; lz < 16; lz++) {
					int wz = (cz << 4) + lz - originZ;
					if (wz < 0 || wz >= size) continue;
					for (int lx = 0; lx < 16; lx++) {
						int wx = (cx << 4) + lx - originX;
						if (wx < 0 || wx >= size) continue;
						int i = ChunkColumns.index(lx, lz);
						top[wz * size + wx] = c.top[i];
						emission[wz * size + wx] = c.emission[i];
					}
				}
			}
		}
	}

	public boolean inside(int x, int z) {
		int lx = x - originX, lz = z - originZ;
		return lx >= 0 && lz >= 0 && lx < size && lz < size;
	}

	/** Surface top at world column, UNKNOWN if outside or unexplored. */
	public int top(int x, int z) {
		int lx = x - originX, lz = z - originZ;
		if (lx < 0 || lz < 0 || lx >= size || lz >= size) return ChunkColumns.UNKNOWN;
		return top[lz * size + lx];
	}

	public int emission(int x, int z) {
		int lx = x - originX, lz = z - originZ;
		if (lx < 0 || lz < 0 || lx >= size || lz >= size) return 0;
		return emission[lz * size + lx];
	}
}
