package dev.maqueta.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active beacons in the loaded area, refreshed every two seconds. A beacon shows its beam on
 * the map only while it is actually active, in the colours of the glass above it.
 */
public final class BeaconTracker {
	/** One beam: base position and its coloured sections (ARGB colour, height) from the bottom up. */
	public record Beam(int x, int y, int z, int[] colors, int[] heights) {
	}

	private final Map<Long, Beam> beams = new ConcurrentHashMap<>();
	private int ticks;

	public List<Beam> beams() {
		return new ArrayList<>(beams.values());
	}

	void tick(Minecraft mc) {
		if (ticks++ % 40 != 0 || mc.level == null || mc.player == null) return;
		ClientLevel level = mc.level;
		int r = mc.options.renderDistance().get();
		int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				LevelChunk chunk = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				int cx = pcx + dx, cz = pcz + dz;
				// Forget beacons of this chunk, then add the ones that are active now.
				beams.values().removeIf(b -> (b.x >> 4) == cx && (b.z >> 4) == cz);
				for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
					if (!(e.getValue() instanceof BeaconBlockEntity beacon)) continue;
					List<BeaconBeamOwner.Section> sections = beacon.getBeamSections();
					if (sections.isEmpty()) continue;
					int[] colors = new int[sections.size()], heights = new int[sections.size()];
					for (int k = 0; k < sections.size(); k++) {
						colors[k] = sections.get(k).getColor();
						heights[k] = sections.get(k).getHeight();
					}
					BlockPos p = e.getKey();
					beams.put(p.asLong(), new Beam(p.getX(), p.getY(), p.getZ(), colors, heights));
				}
			}
		}
	}
}
