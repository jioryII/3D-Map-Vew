package dev.maqueta.client.world;

import dev.maqueta.client.render.GpuMesh;
import dev.maqueta.core.Material;
import dev.maqueta.core.cave.CaveMesher;
import dev.maqueta.core.cave.VoxelVolume;
import dev.maqueta.core.mesh.MeshData;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects when the player is underground and keeps a sliced 3D mesh of the connected space
 * around them. Entering/leaving uses hysteresis so the map does not flicker at cave mouths.
 */
public final class CaveTracker implements AutoCloseable {
	private static final int RADIUS = 40;
	private static final int BELOW = 18;
	private static final int ABOVE = 6;

	private boolean active;
	private int enterTicks, exitTicks;
	private int lastX = Integer.MIN_VALUE, lastY, lastZ;
	private int ticksSinceBuild;
	private volatile boolean building;
	private final AtomicReference<Pending> pending = new AtomicReference<>();
	private GpuMesh mesh;
	private int cutY;
	private int reached;
	private final int anchorX, anchorZ;
	private boolean forced;

	private record Pending(MeshData data, int cutY, int reached) {
	}

	public CaveTracker(int anchorX, int anchorZ) {
		this.anchorX = anchorX;
		this.anchorZ = anchorZ;
	}

	public boolean active() {
		return active || forced;
	}

	public void toggleForced() {
		forced = !forced;
		lastX = Integer.MIN_VALUE;
	}

	public boolean forced() {
		return forced;
	}

	public int cutY() {
		return cutY;
	}

	public int reachedCells() {
		return reached;
	}

	public GpuMesh mesh() {
		return mesh;
	}

	public void tick(ClientLevel level, Player player) {
		BlockPos eye = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
		boolean under = isUnderground(level, eye);
		if (under) {
			exitTicks = 0;
			if (!active && ++enterTicks > 20) active = true;
		} else {
			enterTicks = 0;
			if (active && ++exitTicks > 20) active = false;
		}
		if (!active()) {
			return;
		}
		ticksSinceBuild++;
		int px = eye.getX(), py = (int) Math.floor(player.getY()), pz = eye.getZ();
		boolean moved = lastX == Integer.MIN_VALUE || Math.abs(px - lastX) + Math.abs(py - lastY) + Math.abs(pz - lastZ) >= 3;
		if (!building && (moved || ticksSinceBuild > 60)) {
			lastX = px;
			lastY = py;
			lastZ = pz;
			ticksSinceBuild = 0;
			capture(level, px, py, pz);
		}
	}

	private static boolean isUnderground(ClientLevel level, BlockPos eye) {
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, eye.getX(), eye.getZ());
		if (level.dimensionType().hasCeiling()) {
			return true;
		}
		int sky = level.getBrightness(LightLayer.SKY, eye);
		return surface > eye.getY() + 3 && sky <= 4;
	}

	private void capture(ClientLevel level, int px, int py, int pz) {
		int y0 = py - BELOW, y1 = py + ABOVE;
		int x0 = px - RADIUS, z0 = pz - RADIUS;
		int size = RADIUS * 2;
		int cut = py + 2;
		int minY = level.getMinY();

		// Main thread: copy only the section containers we need.
		int cx0 = x0 >> 4, cx1 = (x0 + size - 1) >> 4, cz0 = z0 >> 4, cz1 = (z0 + size - 1) >> 4;
		int s0 = Math.max(0, (y0 - minY) >> 4), s1 = (y1 - minY) >> 4;
		int ncx = cx1 - cx0 + 1, ncz = cz1 - cz0 + 1, ns = s1 - s0 + 1;
		@SuppressWarnings("unchecked")
		PalettedContainer<BlockState>[] copies = new PalettedContainer[ncx * ncz * ns];
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				LevelChunkSection[] sections = chunk.getSections();
				for (int s = s0; s <= s1 && s < sections.length; s++) {
					LevelChunkSection sec = sections[s];
					if (sec == null || sec.hasOnlyAir()) continue;
					copies[((cz - cz0) * ncx + (cx - cx0)) * ns + (s - s0)] = sec.getStates().copy();
				}
			}
		}
		building = true;
		Workers.submit(-1, () -> {
			try {
				VoxelVolume v = new VoxelVolume(x0, y0, z0, size, y1 - y0 + 1, size);
				for (int ly = 0; ly < v.sizeY; ly++) {
					int wy = y0 + ly;
					int s = ((wy - minY) >> 4) - s0;
					if (s < 0 || s >= ns) continue;
					for (int lz = 0; lz < size; lz++) {
						int wz = z0 + lz;
						for (int lx = 0; lx < size; lx++) {
							int wx = x0 + lx;
							PalettedContainer<BlockState> c = copies[(((wz >> 4) - cz0) * ncx + ((wx >> 4) - cx0)) * ns + s];
							if (c == null) continue;
							BlockState st = c.get(wx & 15, (wy - minY) & 15, wz & 15);
							BlockInfo info = BlockInfo.of(st);
							switch (info.kind()) {
								case SOLID -> v.set(lx, ly, lz, info.material(), info.color(), info.emission());
								case WATER -> v.set(lx, ly, lz, Material.WATER, 0x3F76E4, 0);
								case DECORATION -> {
									if (info.emission() > 0) v.set(lx, ly, lz, Material.NONE, 0, info.emission());
								}
								default -> {
								}
							}
						}
					}
				}
				CaveMesher.Result r = CaveMesher.build(v, px, py, pz, cut, 120_000, anchorX, anchorZ);
				pending.set(new Pending(r.mesh(), cut, r.reachedCells()));
			} finally {
				building = false;
			}
		});
	}

	/** Render thread: swap in a finished cave mesh. */
	public void processUpload() {
		Pending p = pending.getAndSet(null);
		if (p == null) return;
		if (mesh != null) {
			mesh.close();
			mesh = null;
		}
		cutY = p.cutY;
		reached = p.reached;
		if (!p.data.isEmpty()) mesh = GpuMesh.upload("Maqueta cave", p.data);
	}

	@Override
	public void close() {
		if (mesh != null) mesh.close();
		mesh = null;
	}
}
