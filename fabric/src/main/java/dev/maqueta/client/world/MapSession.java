package dev.maqueta.client.world;

import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.render.MeshCache;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;
import dev.maqueta.core.io.RegionStorage;
import dev.maqueta.core.light.TimeGrading;
import dev.maqueta.core.marker.Trail;
import dev.maqueta.core.marker.Waypoint;
import dev.maqueta.core.marker.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything the map knows about the current world and dimension. Created when the player
 * enters a level, closed (and flushed to disk) when they leave or change dimension.
 */
public final class MapSession implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("maqueta");
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Maqueta region I/O");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});
	private static final int MAX_CAPTURE_IN_FLIGHT = 12;
	private static final long CAPTURE_BUDGET_NS = 600_000L;
	private static MapSession current;

	public final String worldId;
	public final String dimension;
	public final TimeGrading.Dimension dimensionKind;
	/**
	 * One explored layer of the world: its columns, meshes, heightmap and files. Every
	 * dimension has the main one; the Nether also keeps the world above its bedrock roof.
	 */
	static final class Layer {
		final ColumnStore store = new ColumnStore();
		final MeshCache meshes;
		final dev.maqueta.client.render.HeightTexture heights;
		final RegionStorage storage;
		Map<Long, Map<Long, ChunkColumns>> dirty = new HashMap<>();

		Layer(int anchorX, int anchorZ, int boardY, Path dir) {
			this.meshes = new MeshCache(store, anchorX, anchorZ, boardY);
			this.heights = new dev.maqueta.client.render.HeightTexture(store, anchorX, anchorZ);
			this.storage = new RegionStorage(dir);
		}
	}

	private final Layer main;
	/** Above the Nether roof; null in dimensions without one. */
	private final Layer roof;
	private volatile Layer active;
	/** The layer being shown (switches with the Nether roof view). */
	public volatile ColumnStore store;
	public volatile MeshCache meshes;
	public volatile dev.maqueta.client.render.HeightTexture heights;
	/** Bedrock roof of a dimension with a ceiling (Nether: 127); Integer.MIN_VALUE otherwise. */
	public final int roofY;
	public final RouteTracker route = new RouteTracker(this);
	public final EntityTracker entities = new EntityTracker(this);
	public final BeaconTracker beacons = new BeaconTracker();
	public final WaypointStore waypoints;
	public final Trail trail = new Trail(4096, 2.5f);
	public final CaveTracker cave;
	public final int anchorX, anchorZ;
	public final boolean hasCeiling;
	private ClientLevel level;
	private final Set<Long> pendingCaptures = new LinkedHashSet<>();
	private final AtomicInteger capturesInFlight = new AtomicInteger();
	private final Set<Long> dirtyChunks = new HashSet<>();
	private final Map<Long, Long> lastCapture = new HashMap<>();
	private volatile boolean closed;
	private int ticks;
	private boolean wasDead;
	private boolean fairPlay;
	private boolean radarAllowed = true;
	private boolean caveAllowed = true;
	private boolean minimapAllowed = true;

	private MapSession(ClientLevel level, Minecraft mc, String worldId, WaypointStore previousWaypoints) {
		this.level = level;
		this.worldId = worldId;
		this.dimension = level.dimension().identifier().toString();
		this.dimensionKind = level.dimension() == Level.NETHER ? TimeGrading.Dimension.NETHER
			: level.dimension() == Level.END ? TimeGrading.Dimension.END : TimeGrading.Dimension.OVERWORLD;
		this.hasCeiling = level.dimensionType().hasCeiling();
		int px = mc.player != null ? mc.player.getBlockX() : 0;
		int pz = mc.player != null ? mc.player.getBlockZ() : 0;
		this.anchorX = Math.floorDiv(px, 512) * 512;
		this.anchorZ = Math.floorDiv(pz, 512) * 512;
		int boardY = level.getSeaLevel() - 3;
		if (hasCeiling) boardY = 28;
		if (dimensionKind == TimeGrading.Dimension.END) boardY = 30;
		this.cave = new CaveTracker(anchorX, anchorZ);
		Path base = mc.gameDirectory.toPath().resolve("maqueta").resolve(worldId);
		this.main = new Layer(anchorX, anchorZ, boardY, base.resolve(sanitize(dimension)));
		if (hasCeiling) {
			this.roofY = level.getMinY() + level.dimensionType().logicalHeight() - 1;
			this.roof = new Layer(anchorX, anchorZ, roofY - 14, base.resolve(sanitize(dimension) + "_roof"));
			this.roof.meshes.setMemoryBudget(256L * 1024 * 1024);
		} else {
			this.roofY = Integer.MIN_VALUE;
			this.roof = null;
		}
		show(roof != null && mc.player != null && mc.player.getY() >= roofY ? roof : main);
		this.waypoints = previousWaypoints == null ? new WaypointStore(base.resolve("waypoints.txt")) : previousWaypoints;
		if (previousWaypoints == null) waypoints.load();
		IO.execute(() -> {
			if (closed) return;
			loadSavedRegions(main, px, pz);
			if (roof != null) loadSavedRegions(roof, px, pz);
		});
		if (MaquetaConfig.get().importOtherMaps && !hasCeiling) {
			int seaLevel = level.getSeaLevel();
			Path gameDir = mc.gameDirectory.toPath();
			ServerData server = mc.getCurrentServer();
			String worldName = mc.getSingleplayerServer() == null ? null : mc.getSingleplayerServer().getWorldData().getLevelName();
			String serverIp = server == null ? null : server.ip;
			String serverName = server == null ? null : server.name;
			IO.execute(() -> {
				if (!closed) importOtherMaps(gameDir, worldName, serverIp, serverName, px, pz, seaLevel);
			});
		}
	}

	private void show(Layer l) {
		active = l;
		store = l.store;
		meshes = l.meshes;
		heights = l.heights;
	}

	/** Does this dimension have a roof with its own layer above it (the Nether)? */
	public boolean hasRoofLayer() {
		return roof != null;
	}

	/** Showing the world above the Nether roof instead of the inside? */
	public boolean roofView() {
		return roof != null && active == roof;
	}

	/** While the full map is open the layer stays where the user put it; otherwise it follows the player. */
	public volatile boolean layerPinned;

	public void setRoofView(boolean above) {
		if (roof == null) return;
		show(above ? roof : main);
	}

	/** Where the player is: above the bedrock roof or inside. */
	public boolean playerAboveRoof() {
		Minecraft mc = Minecraft.getInstance();
		return roof != null && mc.player != null && mc.player.getY() >= roofY;
	}

	/**
	 * The session for the level a chunk belongs to. Chunks for a new level can arrive before
	 * the next tick switches sessions. That tick will seed the queue with already loaded chunks.
	 */
	public static MapSession forLevel(ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		if (level == null || mc.level != level) return null;
		return current != null && current.level == level ? current : null;
	}

	public static MapSession get() {
		return current;
	}

	/** Called every client tick: makes sure the session matches the current level. */
	public static MapSession sync(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			if (current != null) {
				current.close();
				current = null;
			}
			return null;
		}
		if (current == null || current.level != level) {
			String id = worldId(mc);
			WaypointStore previousWaypoints = current != null && current.worldId.equals(id) ? current.waypoints : null;
			if (current != null) current.close();
			current = new MapSession(level, mc, id, previousWaypoints);
			current.seedCaptures(mc);
		}
		return current;
	}

	private void seedCaptures(Minecraft mc) {
		int radius = mc.options.renderDistance().get();
		int centerX = mc.player.getBlockX() >> 4, centerZ = mc.player.getBlockZ() >> 4;
		// Enumerate closest first. The queue stores coordinates, never live chunks.
		for (int ring = 0; ring <= radius; ring++) {
			for (int x = -ring; x <= ring; x++) {
				enqueueLoaded(centerX + x, centerZ - ring);
				if (ring > 0) enqueueLoaded(centerX + x, centerZ + ring);
			}
			for (int z = -ring + 1; z < ring; z++) {
				enqueueLoaded(centerX - ring, centerZ + z);
				enqueueLoaded(centerX + ring, centerZ + z);
			}
		}
	}

	private void enqueueLoaded(int x, int z) {
		if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) {
			pendingCaptures.add(ChunkColumns.key(x, z));
		}
	}

	public static void shutdown() {
		if (current != null) {
			current.close();
			current = null;
		}
	}

	public static void stop() {
		shutdown();
		IO.shutdown();
		try {
			while (!IO.awaitTermination(1, TimeUnit.SECONDS)) {
				// The client is exiting. Let committed exploration finish writing.
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String worldId(Minecraft mc) {
		if (mc.getSingleplayerServer() != null) {
			return "sp_" + sanitize(mc.getSingleplayerServer().getWorldData().getLevelName());
		}
		ServerData server = mc.getCurrentServer();
		if (server != null) return "mp_" + sanitize(server.ip);
		return "unknown";
	}

	private static String sanitize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
	}

	private void loadSavedRegions(Layer layer, int px, int pz) {
		List<int[]> regions = layer.storage.listRegions();
		regions.sort((a, b) -> Double.compare(dist2(a, px, pz), dist2(b, px, pz)));
		readNextRegion(layer, regions.iterator());
	}

	private void readNextRegion(Layer layer, Iterator<int[]> regions) {
		if (closed || !regions.hasNext()) return;
		int[] region = regions.next();
		try {
			for (ChunkColumns cols : layer.storage.read(region[0], region[1])) publish(layer, cols, false);
		} catch (IOException e) {
			LOG.warn("Could not read map region {} {}", region[0], region[1], e);
		}
		// Yield after one region so portal flushes and the other layer can use the I/O thread.
		continueIo(() -> readNextRegion(layer, regions));
	}

	/** Areas JourneyMap already charted, as flat sketches. */
	private void importOtherMaps(Path gameDir, String worldName, String serverIp, String serverName, int px, int pz, int seaLevel) {
		List<Path> files = new ArrayList<>(OtherMapsImport.findRegions(gameDir, worldName, serverIp, serverName, dimension));
		files.sort((a, b) -> Double.compare(dist2(OtherMapsImport.regionOf(a), px, pz), dist2(OtherMapsImport.regionOf(b), px, pz)));
		importNextRegion(files.iterator(), seaLevel);
	}

	private void importNextRegion(Iterator<Path> files, int seaLevel) {
		if (closed || !files.hasNext()) return;
		OtherMapsImport.read(files.next(), seaLevel, cols -> publish(main, cols, false));
		continueIo(() -> importNextRegion(files, seaLevel));
	}

	private synchronized void continueIo(Runnable task) {
		if (!closed) IO.execute(task);
	}

	private static double dist2(int[] region, int px, int pz) {
		double cx = region[0] * 512 + 256 - px, cz = region[1] * 512 + 256 - pz;
		return cx * cx + cz * cz;
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (level == this.level) pendingCaptures.add(ChunkColumns.key(chunk.getPos().x(), chunk.getPos().z()));
	}

	private void capturePending() {
		if (pendingCaptures.isEmpty() || capturesInFlight.get() >= MAX_CAPTURE_IN_FLIGHT) return;
		long start = System.nanoTime();
		Map<Long, PalettedContainer<BlockState>[]> copies = new HashMap<>();
		Iterator<Long> keys = pendingCaptures.iterator();
		for (int count = 0; count < 4 && keys.hasNext() && capturesInFlight.get() < MAX_CAPTURE_IN_FLIGHT; count++) {
			long key = keys.next();
			keys.remove();
			LevelChunk chunk = level.getChunkSource().getChunk(ChunkColumns.keyX(key), ChunkColumns.keyZ(key), ChunkStatus.FULL, false);
			if (chunk != null) {
				ChunkCapture.Capture capture = ChunkCapture.snapshot(level, chunk, System.currentTimeMillis(), copies);
				lastCapture.put(key, (long) ticks);
				capturesInFlight.incrementAndGet();
				Workers.submit(priorityFor(capture.chunkX(), capture.chunkZ()), () -> {
					try {
						if (closed) return;
						publish(main, capture.process(roofY), true);
						if (roof != null && !closed) publish(roof, capture.process(Integer.MIN_VALUE), true);
					} finally {
						capturesInFlight.decrementAndGet();
					}
				});
			}
			if (System.nanoTime() - start >= CAPTURE_BUDGET_NS) break;
		}
	}

	private synchronized void publish(Layer layer, ChunkColumns cols, boolean captured) {
		if (closed) return;
		ChunkColumns existing = layer.store.get(cols.chunkX, cols.chunkZ);
		if (existing != null && (cols.imported || (!existing.imported
			&& (!captured || existing.capturedAt > cols.capturedAt)))) return;
		layer.store.put(cols);
		if (captured) {
			long region = RegionStorage.regionKey(cols.chunkX, cols.chunkZ);
			layer.dirty.computeIfAbsent(region, ignored -> new HashMap<>()).put(cols.key(), cols);
		}
		layer.meshes.invalidate(cols.chunkX, cols.chunkZ, captured ? 2 : 1);
		layer.heights.markDirty(cols.chunkX, cols.chunkZ);
	}

	private double priorityFor(int cx, int cz) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return 100;
		double dx = cx * 16 + 8 - mc.player.getX(), dz = cz * 16 + 8 - mc.player.getZ();
		return Math.sqrt(dx * dx + dz * dz) / 16.0;
	}

	public void onBlockChanged(BlockPos pos) {
		dirtyChunks.add(ChunkColumns.key(pos.getX() >> 4, pos.getZ() >> 4));
	}

	public void tick(Minecraft mc) {
		ticks++;
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) return;

		trail.setMaxLength(MaquetaConfig.get().trailLength);
		trail.record(mc.player.getX(), mc.player.getY(), mc.player.getZ());

		// Re-capture edited chunks, at most a few per tick and not more than twice a second each.
		if (!dirtyChunks.isEmpty() && ticks % 5 == 0) {
			List<Long> done = new ArrayList<>();
			for (long key : dirtyChunks) {
				Long last = lastCapture.get(key);
				if (last != null && ticks - last < 10) continue;
				LevelChunk c = level.getChunkSource().getChunk(ChunkColumns.keyX(key), ChunkColumns.keyZ(key), ChunkStatus.FULL, false);
				if (c != null) onChunkLoad(level, c);
				done.add(key);
				if (done.size() >= 4) break;
			}
			done.forEach(dirtyChunks::remove);
		}

		capturePending();
		if (caveAllowed) {
			cave.tick(level, mc.player);
		}
		route.tick(mc);
		entities.tick(mc);
		beacons.tick(mc);
		if (roof != null && !layerPinned && roofView() != playerAboveRoof()) setRoofView(playerAboveRoof());
		if (ticks % 20 == 0) {
			lastCapture.values().removeIf(last -> ticks - last >= 10);
			prewarm(mc);
		}

		// Death marker.
		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead) {
			waypoints.setDeath(new Waypoint("†", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), 0xE8574A, dimension, Waypoint.FLAG_DEATH));
		}
		wasDead = dead;

		if (ticks % 600 == 0) flush();
	}

	/**
	 * Bakes meshes in the background before the map is opened: full detail near the player,
	 * coarse levels further out, a bounded number per pass. Opening the map then shows
	 * terrain that is already prepared instead of rebuilding it.
	 */
	private void prewarm(Minecraft mc) {
		if (!MaquetaConfig.get().minimapEnabled || !minimapAllowed() || !pendingCaptures.isEmpty() || Workers.pending() > 12) return;
		int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
		int budget = 8;
		List<ChunkColumns> nearby = new ArrayList<>();
		store.collect(pcx, pcz, 12, (x, z) -> true, nearby);
		for (ChunkColumns c : nearby) {
			if (budget <= 0) break;
			int dx = c.chunkX - pcx, dz = c.chunkZ - pcz;
			int d2 = dx * dx + dz * dz;
			if (d2 > 12 * 12 || meshes.isReady(c.chunkX, c.chunkZ, 0)) continue;
			meshes.warm(c.chunkX, c.chunkZ, 0, 50 + Math.sqrt(d2));
			budget--;
		}
	}

	private void flush() {
		IO.execute(() -> {
			writeDirty(main);
			if (roof != null) writeDirty(roof);
		});
	}

	private void writeDirty(Layer layer) {
		Map<Long, Map<Long, ChunkColumns>> dirty;
		synchronized (this) {
			dirty = layer.dirty;
			layer.dirty = new HashMap<>();
		}
		for (Map.Entry<Long, Map<Long, ChunkColumns>> entry : dirty.entrySet()) {
			long key = entry.getKey();
			int rx = ChunkColumns.keyX(key), rz = ChunkColumns.keyZ(key);
			try {
				// A region may not have been streamed into RAM yet. Keep its unchanged chunks.
				Map<Long, ChunkColumns> merged = new HashMap<>();
				for (ChunkColumns cols : layer.storage.read(rx, rz)) merged.put(cols.key(), cols);
				merged.putAll(entry.getValue());
				layer.storage.write(rx, rz, new ArrayList<>(merged.values()));
			} catch (IOException e) {
				synchronized (this) {
					Map<Long, ChunkColumns> retry = layer.dirty.computeIfAbsent(key, ignored -> new HashMap<>());
					entry.getValue().forEach(retry::putIfAbsent);
				}
				LOG.warn("Could not save Maqueta map region {} {}", rx, rz, e);
			}
		}
	}

	// --- Fair play (Xaero's chat codes, design 11) ---

	public void applyServerCodes(String message) {
		if (message.contains("§f§a§i§r§x§a§e§r§o")) fairPlay = true;
		if (message.contains("§n§o§m§i§n§i§m§a§p")) minimapAllowed = false;
		if (message.contains("§r§e§s§e§t§x§a§e§r§o")) {
			fairPlay = false;
			minimapAllowed = true;
			radarAllowed = true;
			caveAllowed = true;
		}
		if (fairPlay) {
			radarAllowed = false;
			caveAllowed = false;
		}
	}

	public boolean radarAllowed() {
		return radarAllowed && !MaquetaConfig.get().safeMode;
	}

	public boolean caveLayerAllowed() {
		return caveAllowed;
	}

	public boolean minimapAllowed() {
		return minimapAllowed;
	}

	public boolean fairPlay() {
		return fairPlay || MaquetaConfig.get().safeMode;
	}

	public int ticks() {
		return ticks;
	}

	@Override
	public void close() {
		synchronized (this) {
			if (closed) return;
			closed = true;
		}
		level = null;
		pendingCaptures.clear();
		Workers.clearQueue();
		flush();
		main.meshes.close();
		main.heights.close();
		if (roof != null) {
			roof.meshes.close();
			roof.heights.close();
		}
		cave.close();
	}
}
