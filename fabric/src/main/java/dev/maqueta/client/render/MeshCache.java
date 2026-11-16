package dev.maqueta.client.render;

import dev.maqueta.client.world.Workers;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;
import dev.maqueta.core.mesh.MeshData;
import dev.maqueta.core.mesh.TerrainMesher;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.PriorityQueue;
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
	private static final int MAX_PENDING = 32;

	private final ColumnStore store;
	private final int anchorX, anchorZ;
	private final int boardY;
	private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
	private final LinkedHashMap<GpuMesh, Resident> resident = new LinkedHashMap<>(64, 0.75f, true);
	private static final Comparator<Request> BY_DISTANCE = Comparator.comparingDouble(Request::priority);
	private final PriorityQueue<Request> requests = new PriorityQueue<>(MAX_PENDING, BY_DISTANCE.reversed());
	private int pendingBakes;
	private volatile boolean closed;
	private long gpuBytes;
	private long frame;
	private long maxGpuBytes = 640L * 1024 * 1024;

	private static final class Entry {
		final GpuMesh[] meshes = new GpuMesh[LODS];
		final int[] builtVersion = {-1, -1, -1, -1};
		final boolean[] inFlight = new boolean[LODS];
		volatile int version;
	}

	private record Request(long key, int lod, double priority) {
	}

	private record Resident(Entry entry, int lod) {
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
				Entry e = entries.get(ChunkColumns.key(chunkX + dx, chunkZ + dz));
				if (e != null) e.version++;
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
		Entry e = entries.get(key);
		int room = MAX_PENDING - pendingBakes;
		if (room > 0 && needsBake(e, lod)) {
			if (requests.size() < room) requests.add(new Request(key, lod, priority));
			else if (priority < requests.element().priority) {
				requests.poll();
				requests.add(new Request(key, lod, priority));
			}
		}
		if (e == null) return null;
		GpuMesh best = e.meshes[lod];
		if (best == null) {
			// Prefer coarser stand-ins (cheap), then finer ones.
			for (int d = 1; d < LODS && best == null; d++) {
				if (lod + d < LODS && e.meshes[lod + d] != null) best = e.meshes[lod + d];
				else if (lod - d >= 0 && e.meshes[lod - d] != null) best = e.meshes[lod - d];
			}
		}
		if (best != null) {
			best.lastUsedFrame = frame;
			resident.get(best); // Move to the end of the access-ordered eviction list.
		}
		return best;
	}

	private static boolean needsBake(Entry entry, int lod) {
		return entry == null || (entry.builtVersion[lod] != entry.version && !entry.inFlight[lod]);
	}

	public void scheduleVisible() {
		// Start foremost chunks immediately. A distant map view otherwise fills the bounded queue first.
		if (requests.isEmpty()) return;
		Request[] ordered = requests.toArray(Request[]::new);
		requests.clear();
		java.util.Arrays.sort(ordered, BY_DISTANCE);
		for (Request request : ordered) {
			long key = request.key;
			warm(ChunkColumns.keyX(key), ChunkColumns.keyZ(key), request.lod, request.priority);
		}
	}

	public void warm(int chunkX, int chunkZ, int lod, double priority) {
		if (pendingBakes >= MAX_PENDING) return;
		long key = ChunkColumns.key(chunkX, chunkZ);
		Entry e = entries.computeIfAbsent(key, ignored -> new Entry());
		if (needsBake(e, lod)) schedule(key, chunkX, chunkZ, lod, e, priority);
	}

	private void schedule(long key, int chunkX, int chunkZ, int lod, Entry e, double priority) {
		e.inFlight[lod] = true;
		pendingBakes++;
		int version = e.version;
		Workers.submit(priority + lod * 0.5, () -> {
			MeshData data = null;
			try {
				if (!closed && e.version == version) {
					data = new TerrainMesher(store, boardY, TileAtlas.get()).build(chunkX, chunkZ, lod, anchorX, anchorZ);
				}
			} finally {
				synchronized (ready) {
					if (!closed) ready.add(new Ready(key, lod, version, data));
				}
			}
		});
	}

	/** Uploads finished meshes until the time budget runs out. Render thread only. */
	public int processUploads(long budgetNanos) {
		long start = System.nanoTime();
		int uploaded = 0;
		Ready r;
		while (System.nanoTime() - start < budgetNanos && (r = ready.poll()) != null) {
			pendingBakes--;
			Entry e = entries.get(r.key);
			if (e == null) continue;
			e.inFlight[r.lod] = false;
			if (r.data == null || (r.version != e.version && e.meshes[r.lod] != null)) continue;
			GpuMesh old = e.meshes[r.lod];
			if (old != null) {
				gpuBytes -= old.bytes;
				resident.remove(old);
				old.close();
				e.meshes[r.lod] = null;
			}
			if (!r.data.isEmpty()) {
				GpuMesh mesh = GpuMesh.upload("Maqueta chunk " + ChunkColumns.keyX(r.key) + "," + ChunkColumns.keyZ(r.key) + " lod" + r.lod, r.data);
				mesh.lastUsedFrame = frame;
				e.meshes[r.lod] = mesh;
				resident.put(mesh, new Resident(e, r.lod));
				gpuBytes += mesh.bytes;
			}
			e.builtVersion[r.lod] = r.version;
			uploaded++;
		}
		if (gpuBytes > maxGpuBytes) evict();
		return uploaded;
	}

	private void evict() {
		long target = (long) (maxGpuBytes * 0.8);
		var iterator = resident.entrySet().iterator();
		while (gpuBytes > target && iterator.hasNext()) {
			var oldest = iterator.next();
			GpuMesh mesh = oldest.getKey();
			if (mesh.lastUsedFrame >= frame - 2) break; // Never evict what is on screen.
			Resident slot = oldest.getValue();
			slot.entry.meshes[slot.lod] = null;
			slot.entry.builtVersion[slot.lod] = -1;
			gpuBytes -= mesh.bytes;
			mesh.close();
			iterator.remove();
		}
	}

	/** True when the mesh for this LOD is baked, current and resident (or being baked). */
	public boolean isReady(int chunkX, int chunkZ, int lod) {
		Entry e = entries.get(ChunkColumns.key(chunkX, chunkZ));
		return e != null && (e.inFlight[lod] || e.builtVersion[lod] == e.version);
	}

	public int pendingUploads() {
		return ready.size();
	}

	@Override
	public void close() {
		synchronized (ready) {
			closed = true;
			ready.clear();
		}
		for (GpuMesh mesh : resident.keySet()) mesh.close();
		resident.clear();
		entries.clear();
		gpuBytes = 0;
	}
}
