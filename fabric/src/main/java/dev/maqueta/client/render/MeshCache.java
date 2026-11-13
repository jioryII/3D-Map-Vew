package dev.maqueta.client.render;

import dev.maqueta.client.world.Workers;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;
import dev.maqueta.core.mesh.MeshData;
import dev.maqueta.core.mesh.TerrainMesher;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GPU meshes per chunk and level of detail.
 * <ul>
 *   <li>Baking happens on workers; uploads on the render thread under a time budget
 *       (design 12.1: at most ~1 ms per frame).</li>
 *   <li>Requests are de-duplicated and versioned, stale results are dropped.</li>
 *   <li>While a finer LOD bakes, the closest available LOD is drawn instead.</li>
 *   <li>GPU memory is capped; least recently drawn meshes are evicted first.</li>
 * </ul>
 */
public final class MeshCache implements AutoCloseable {
	public static final int LODS = 4;

	private final ColumnStore store;
	private final int anchorX, anchorZ;
	private final int boardY;
	private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
	private long gpuBytes;
	private long frame;
	private long maxGpuBytes = 384L * 1024 * 1024;

	private static final class Entry {
		final GpuMesh[] meshes = new GpuMesh[LODS];
		final int[] builtVersion = {-1, -1, -1, -1};
		final boolean[] inFlight = new boolean[LODS];
		volatile int version;
	}

	private record Ready(long key, int lod, int version, MeshData data) {
	}

	public MeshCache(ColumnStore store, int anchorX, int anchorZ, int boardY) {
		this.store = store;
		this.anchorX = anchorX;
		this.anchorZ = anchorZ;
		this.boardY = boardY;
	}

	public int anchorX() {
		return anchorX;
	}

	public int anchorZ() {
		return anchorZ;
	}

	public int boardY() {
		return boardY;
	}

	public void setMemoryBudget(long bytes) {
		this.maxGpuBytes = bytes;
	}

	public long gpuBytes() {
		return gpuBytes;
	}

	/** Chunk data changed: its mesh and the neighbours whose walls/shadows depend on it. */
	public void invalidate(int chunkX, int chunkZ, int radius) {
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				if (!store.contains(chunkX + dx, chunkZ + dz)) continue;
				Entry e = entries.computeIfAbsent(ChunkColumns.key(chunkX + dx, chunkZ + dz), k -> new Entry());
				e.version++;
			}
		}
	}

	public void beginFrame() {
		frame++;
	}

	/**
	 * Mesh to draw for a chunk at the wanted LOD. Schedules a bake if needed and returns
	 * the best mesh available right now (possibly another LOD, possibly null).
	 */
	public GpuMesh request(int chunkX, int chunkZ, int lod, double priority) {
		long key = ChunkColumns.key(chunkX, chunkZ);
		Entry e = entries.computeIfAbsent(key, k -> new Entry());
		if (e.builtVersion[lod] != e.version && !e.inFlight[lod]) {
			schedule(key, chunkX, chunkZ, lod, e, priority);
		}
		GpuMesh best = e.meshes[lod];
		if (best == null) {
			// Prefer coarser stand-ins (cheap), then finer ones.
			for (int d = 1; d < LODS && best == null; d++) {
				if (lod + d < LODS && e.meshes[lod + d] != null) best = e.meshes[lod + d];
				else if (lod - d >= 0 && e.meshes[lod - d] != null) best = e.meshes[lod - d];
			}
		}
		if (best != null) best.lastUsedFrame = frame;
		return best;
	}

	private void schedule(long key, int chunkX, int chunkZ, int lod, Entry e, double priority) {
		e.inFlight[lod] = true;
		int version = e.version;
		Workers.submit(priority + lod * 0.5, () -> {
			if (entries.get(key) != e || e.version != version) {
				e.inFlight[lod] = false;
				return;
			}
			MeshData data = new TerrainMesher(store, boardY).build(chunkX, chunkZ, lod, anchorX, anchorZ);
			ready.add(new Ready(key, lod, version, data));
		});
	}

	/** Uploads finished meshes until the time budget runs out. Render thread only. */
	public int processUploads(long budgetNanos) {
		long start = System.nanoTime();
		int n = 0;
		Ready r;
		while ((r = ready.poll()) != null) {
			Entry e = entries.get(r.key);
			if (e == null) continue;
			e.inFlight[r.lod] = false;
			if (r.version != e.version && e.meshes[r.lod] != null) {
				continue; // a newer bake is coming; keep showing the old one
			}
			GpuMesh old = e.meshes[r.lod];
			if (old != null) {
				gpuBytes -= old.bytes;
				old.close();
				e.meshes[r.lod] = null;
			}
			if (!r.data.isEmpty()) {
				GpuMesh mesh = GpuMesh.upload("Maqueta chunk " + ChunkColumns.keyX(r.key) + "," + ChunkColumns.keyZ(r.key) + " lod" + r.lod, r.data);
				mesh.lastUsedFrame = frame;
				e.meshes[r.lod] = mesh;
				gpuBytes += mesh.bytes;
			}
			e.builtVersion[r.lod] = r.version;
			n++;
			if (System.nanoTime() - start > budgetNanos) break;
		}
		if (gpuBytes > maxGpuBytes) evict();
		return n;
	}

	private void evict() {
		List<Object[]> all = new ArrayList<>();
		for (Map.Entry<Long, Entry> me : entries.entrySet()) {
			Entry e = me.getValue();
			for (int lod = 0; lod < LODS; lod++) {
				if (e.meshes[lod] != null) all.add(new Object[]{e, lod, e.meshes[lod].lastUsedFrame});
			}
		}
		all.sort((a, b) -> Long.compare((long) a[2], (long) b[2]));
		Iterator<Object[]> it = all.iterator();
		long target = (long) (maxGpuBytes * 0.8);
		while (gpuBytes > target && it.hasNext()) {
			Object[] o = it.next();
			Entry e = (Entry) o[0];
			int lod = (int) o[1];
			if ((long) o[2] >= frame - 2) break; // never evict what is on screen
			GpuMesh m = e.meshes[lod];
			gpuBytes -= m.bytes;
			m.close();
			e.meshes[lod] = null;
			e.builtVersion[lod] = -1;
		}
	}

	public int pendingUploads() {
		return ready.size();
	}

	@Override
	public void close() {
		for (Entry e : entries.values()) {
			for (int i = 0; i < LODS; i++) {
				if (e.meshes[i] != null) {
					e.meshes[i].close();
					e.meshes[i] = null;
				}
			}
		}
		entries.clear();
		ready.clear();
		gpuBytes = 0;
	}
}
