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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the map knows about the current world and dimension. Created when the player
 * enters a level, closed (and flushed to disk) when they leave or change dimension.
 */
public final class MapSession implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("maqueta");
	private static MapSession current;

	public final String worldId;
	public final String dimension;
	public final TimeGrading.Dimension dimensionKind;
	public final ColumnStore store = new ColumnStore();
	public final MeshCache meshes;
	public final WaypointStore waypoints;
	public final Trail trail = new Trail(4096, 2.5f);
	public final CaveTracker cave;
	public final int anchorX, anchorZ;
	public final boolean hasCeiling;
	private final RegionStorage storage;
	private final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();
	private final Set<Long> dirtyChunks = new HashSet<>();
	private final Map<Long, Long> lastCapture = new HashMap<>();
	private int ticks;
	private boolean wasDead;
	private boolean fairPlay;
	private boolean radarAllowed = true;
	private boolean caveAllowed = true;
	private boolean minimapAllowed = true;

	private MapSession(ClientLevel level, Minecraft mc) {
		this.worldId = worldId(mc);
		this.dimension = level.dimension().identifier().toString();
		this.dimensionKind = level.dimension() == Level.NETHER ? TimeGrading.Dimension.NETHER
			: level.dimension() == Level.END ? TimeGrading.Dimension.END : TimeGrading.Dimension.OVERWORLD;
		this.hasCeiling = level.dimensionType().hasCeiling();
		int px = mc.player != null ? mc.player.getBlockX() : 0;
		int pz = mc.player != null ? mc.player.getBlockZ() : 0;
		this.anchorX = Math.floorDiv(px, 512) * 512;
		this.anchorZ = Math.floorDiv(pz, 512) * 512;
		int boardY = level.getSeaLevel() - 18;
		if (hasCeiling) boardY = 10;
		if (dimensionKind == TimeGrading.Dimension.END) boardY = 30;
		this.meshes = new MeshCache(store, anchorX, anchorZ, boardY);
		this.cave = new CaveTracker(anchorX, anchorZ);
		Path base = mc.gameDirectory.toPath().resolve("maqueta").resolve(worldId);
		this.storage = new RegionStorage(base.resolve(sanitize(dimension)));
		this.waypoints = new WaypointStore(base.resolve("waypoints.txt"));
		this.waypoints.load();
		loadSavedRegions(px, pz);
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
		String dim = level.dimension().identifier().toString();
		if (current == null || !current.dimension.equals(dim) || !current.worldId.equals(worldId(mc))) {
			if (current != null) current.close();
			current = new MapSession(level, mc);
			// Capture what is already loaded (joining a world, changing dimension).
			int r = mc.options.renderDistance().get();
			int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					LevelChunk c = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
					if (c != null) current.onChunkLoad(level, c);
				}
			}
		}
		return current;
	}

	public static void shutdown() {
		if (current != null) {
			current.close();
			current = null;
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

	private void loadSavedRegions(int px, int pz) {
		List<int[]> regions = storage.listRegions();
		regions.sort((a, b) -> Double.compare(dist2(a, px, pz), dist2(b, px, pz)));
		for (int[] r : regions) {
			double prio = Math.sqrt(dist2(r, px, pz)) / 16.0 + 4;
			Workers.submit(prio, () -> {
				if (current != this) return;
				try {
					for (ChunkColumns c : storage.read(r[0], r[1])) {
						if (!store.contains(c.chunkX, c.chunkZ)) {
							store.put(c);
							meshes.invalidate(c.chunkX, c.chunkZ, 1);
						}
					}
				} catch (IOException e) {
					LOG.warn("Could not read map region {} {}", r[0], r[1], e);
				}
			});
		}
	}

	private static double dist2(int[] region, int px, int pz) {
		double cx = region[0] * 512 + 256 - px, cz = region[1] * 512 + 256 - pz;
		return cx * cx + cz * cz;
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		int ceilingStart = hasCeiling ? (int) Math.min(level.getMaxY() - 8, Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getY() + 24 : 100) : Integer.MIN_VALUE;
		ChunkCapture.Snapshot snap = ChunkCapture.snapshot(chunk, System.currentTimeMillis(), ceilingStart);
		lastCapture.put(ChunkColumns.key(snap.chunkX(), snap.chunkZ()), (long) ticks);
		double prio = priorityFor(snap.chunkX(), snap.chunkZ());
		Workers.submit(prio, () -> {
			if (current != this) return;
			ChunkColumns cols = snap.process();
			store.put(cols);
			dirtyRegions.add(RegionStorage.regionKey(cols.chunkX, cols.chunkZ));
			meshes.invalidate(cols.chunkX, cols.chunkZ, 2);
		});
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

		if (caveAllowed) {
			cave.tick(level, mc.player);
		}

		// Death marker.
		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead) {
			waypoints.setDeath(new Waypoint("†", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), 0xE8574A, dimension, Waypoint.FLAG_DEATH));
		}
		wasDead = dead;

		if (ticks % 600 == 0) flush(false);
	}

	/** Write explored regions to disk on a worker. */
	public void flush(boolean blocking) {
		if (dirtyRegions.isEmpty()) return;
		List<Long> regions = new ArrayList<>(dirtyRegions);
		dirtyRegions.clear();
		Runnable job = () -> {
			Map<Long, List<ChunkColumns>> byRegion = new HashMap<>();
			for (ChunkColumns c : store.all()) {
				long rk = RegionStorage.regionKey(c.chunkX, c.chunkZ);
				if (regions.contains(rk)) byRegion.computeIfAbsent(rk, k -> new ArrayList<>()).add(c);
			}
			for (Map.Entry<Long, List<ChunkColumns>> e : byRegion.entrySet()) {
				try {
					storage.write(ChunkColumns.keyX(e.getKey()), ChunkColumns.keyZ(e.getKey()), e.getValue());
				} catch (IOException ex) {
					LOG.warn("Could not save map region", ex);
				}
			}
		};
		if (blocking) job.run();
		else Workers.submit(1000, job);
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
		flush(true);
		waypoints.save();
		meshes.close();
		cave.close();
	}
}
