package dev.maqueta.core;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe map of explored chunk columns for one dimension. Readers on worker threads
 * see whole {@link ChunkColumns} objects that are never mutated after publication.
 */
public final class ColumnStore {
	private final ConcurrentHashMap<Long, ChunkColumns> chunks = new ConcurrentHashMap<>();

	public void put(ChunkColumns columns) {
		chunks.put(columns.key(), columns);
	}

	public ChunkColumns get(int chunkX, int chunkZ) {
		return chunks.get(ChunkColumns.key(chunkX, chunkZ));
	}

	public boolean contains(int chunkX, int chunkZ) {
		return chunks.containsKey(ChunkColumns.key(chunkX, chunkZ));
	}

	public Collection<ChunkColumns> all() {
		return chunks.values();
	}

	public int size() {
		return chunks.size();
	}

	public void clear() {
		chunks.clear();
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
