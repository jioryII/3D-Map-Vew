package dev.maqueta.core;

import java.util.Collection;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe map of explored chunk columns for one dimension. Readers on worker threads
 * see whole {@link ChunkColumns} objects that are never mutated after publication.
 */
public final class ColumnStore {
	// Eight chunks per side. Local keys fit in the JVM's cached Integer range.
	public static final int REGION_SIZE = 8;
	private static final int REGION_SHIFT = 3;
	private static final int REGION_MASK = REGION_SIZE - 1;
	private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, ChunkColumns>> regions = new ConcurrentHashMap<>();
	private final LongAdder size = new LongAdder();

	public void put(ChunkColumns columns) {
		long region = regionKey(columns.chunkX, columns.chunkZ);
		var chunks = regions.computeIfAbsent(region, ignored -> new ConcurrentHashMap<>());
		if (chunks.put(localKey(columns.chunkX, columns.chunkZ), columns) == null) size.increment();
	}

	public ChunkColumns get(int chunkX, int chunkZ) {
		var chunks = regions.get(regionKey(chunkX, chunkZ));
		return chunks == null ? null : chunks.get(localKey(chunkX, chunkZ));
	}

	public boolean contains(int chunkX, int chunkZ) {
		return get(chunkX, chunkZ) != null;
	}

	private static long regionKey(int chunkX, int chunkZ) {
		return ChunkColumns.key(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
	}

	private static int localKey(int chunkX, int chunkZ) {
		return ((chunkZ & REGION_MASK) << REGION_SHIFT) | (chunkX & REGION_MASK);
	}

	@FunctionalInterface
	public interface RegionFilter {
		boolean test(int minChunkX, int minChunkZ);
	}

	/** Appends candidates from the requested square. Callers can reject whole regions first. */
	public void collect(int centerX, int centerZ, int radius, RegionFilter filter, Collection<ChunkColumns> out) {
		int minX = centerX - radius, maxX = centerX + radius;
		int minZ = centerZ - radius, maxZ = centerZ + radius;
		int rx0 = minX >> REGION_SHIFT, rx1 = maxX >> REGION_SHIFT;
		int rz0 = minZ >> REGION_SHIFT, rz1 = maxZ >> REGION_SHIFT;
		long cells = (long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1);
		if (cells > regions.size()) {
			regions.forEach((key, chunks) -> {
				int rx = ChunkColumns.keyX(key), rz = ChunkColumns.keyZ(key);
				if (rx >= rx0 && rx <= rx1 && rz >= rz0 && rz <= rz1 && filter.test(rx << REGION_SHIFT, rz << REGION_SHIFT)) {
					collectChunks(chunks, minX, maxX, minZ, maxZ, out);
				}
			});
		} else {
			for (int rz = rz0; rz <= rz1; rz++) {
				for (int rx = rx0; rx <= rx1; rx++) {
					var chunks = regions.get(ChunkColumns.key(rx, rz));
					if (chunks != null && filter.test(rx << REGION_SHIFT, rz << REGION_SHIFT)) {
						collectChunks(chunks, minX, maxX, minZ, maxZ, out);
					}
				}
			}
		}
	}

	private static void collectChunks(ConcurrentHashMap<Integer, ChunkColumns> chunks,
									  int minX, int maxX, int minZ, int maxZ, Collection<ChunkColumns> out) {
		for (ChunkColumns c : chunks.values()) {
			if (c.chunkX >= minX && c.chunkX <= maxX && c.chunkZ >= minZ && c.chunkZ <= maxZ) out.add(c);
		}
	}

	public int size() {
		return size.intValue();
	}

	public void clear() {
		regions.clear();
		size.reset();
	}

	/** Surface height at a world column, or {@link ChunkColumns#UNKNOWN}. */
	public int top(int x, int z) {
		ChunkColumns c = get(x >> 4, z >> 4);
		return c == null ? ChunkColumns.UNKNOWN : c.top[ChunkColumns.index(x & 15, z & 15)];
	}

	/** Solid floor at a world column, or {@link ChunkColumns#UNKNOWN}. */
	public int floor(int x, int z) {
		ChunkColumns c = get(x >> 4, z >> 4);
		return c == null ? ChunkColumns.UNKNOWN : c.floor[ChunkColumns.index(x & 15, z & 15)];
	}

	/** Like {@link #top} but falls back to the given height for unexplored columns. */
	public int topOr(int x, int z, int fallback) {
		int h = top(x, z);
		return h == ChunkColumns.UNKNOWN ? fallback : h;
	}
}
