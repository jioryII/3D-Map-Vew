package dev.maqueta.client.world;

import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.Material;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Two-phase capture (design 7.2):
 * <ol>
 *   <li>{@link #snapshot} runs on the main thread and only copies the paletted section
 *       containers plus the surface heightmap (array copies, well under a millisecond).</li>
 *   <li>{@link Snapshot#process} runs on a worker and turns the copy into {@link ChunkColumns}.</li>
 * </ol>
 */
public final class ChunkCapture {
	private static final int MAX_SCAN = 96;

	private ChunkCapture() {
	}

	public record Snapshot(int chunkX, int chunkZ, int minY, PalettedContainer<BlockState>[] states,
						   PalettedContainer<Holder<Biome>>[] biomes, int[] surface, long time, int ceilingStart) {

		BlockState block(int lx, int y, int lz) {
			int s = (y - minY) >> 4;
			if (s < 0 || s >= states.length || states[s] == null) return null;
			return states[s].get(lx, (y - minY) & 15, lz);
		}

		Biome biome(int lx, int y, int lz) {
			int s = Math.max(0, Math.min(biomes.length - 1, (y - minY) >> 4));
			PalettedContainer<Holder<Biome>> b = biomes[s];
			if (b == null) return null;
			return b.get(lx >> 2, ((y - minY) & 15) >> 2, lz >> 2).value();
		}

		public ChunkColumns process() {
			ChunkColumns out = new ChunkColumns(chunkX, chunkZ);
			out.capturedAt = time;
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					column(out, lx, lz);
				}
			}
			return out;
		}

		private void column(ChunkColumns out, int lx, int lz) {
			int wx = (chunkX << 4) + lx, wz = (chunkZ << 4) + lz;
			int y = surface[ChunkColumns.index(lx, lz)] - 1;
			if (ceilingStart != Integer.MIN_VALUE) {
				// Dimensions with a roof (Nether): start below the roof, skip solid until the first air pocket.
				y = Math.min(y, ceilingStart);
				while (y > minY) {
					BlockState st = block(lx, y, lz);
					if (st == null || BlockInfo.of(st).kind() != BlockInfo.Kind.SOLID) break;
					y--;
				}
			}
			int bottom = Math.max(minY, y - MAX_SCAN);
			int waterTop = Integer.MIN_VALUE;
			int emission = 0;
			int deco = 0;
			boolean snow = false;
			BlockInfo surfaceInfo = null;
			int surfaceY = Integer.MIN_VALUE;
			for (; y >= bottom; y--) {
				BlockState st = block(lx, y, lz);
				if (st == null) continue;
				BlockInfo info = BlockInfo.of(st);
				switch (info.kind()) {
					case AIR -> {
						continue;
					}
					case DECORATION -> {
						emission = Math.max(emission, info.emission());
						if (info.snowCover()) snow = true;
						else if (deco == 0 && waterTop == Integer.MIN_VALUE) deco = tinted(info, lx, y, lz, wx, wz);
						continue;
					}
					case WATER -> {
						if (waterTop == Integer.MIN_VALUE) waterTop = y + 1;
						emission = Math.max(emission, info.emission());
						continue;
					}
					case SOLID -> {
						surfaceInfo = info;
						surfaceY = y;
					}
				}
				break;
			}
			if (surfaceInfo == null) {
				if (waterTop == Integer.MIN_VALUE) return; // void column: leave unknown
				surfaceY = bottom;
				surfaceInfo = new BlockInfo(BlockInfo.Kind.SOLID, Material.SAND, 0xC8B080, BlockInfo.Tint.NONE, 0, false, false);
			}
			emission = Math.max(emission, surfaceInfo.emission());
			int surfColor = tinted(surfaceInfo, lx, surfaceY, lz, wx, wz);
			Material surfMat = surfaceInfo.material();
			if (snow && waterTop == Integer.MIN_VALUE) {
				surfMat = Material.SNOW;
				surfColor = 0xF4F8FA;
			}

			// What a cliff shows below the surface layer.
			Material subMat = surfMat.subsurface();
			int subColor = 0;
			for (int sy = surfaceY - 1; sy >= Math.max(minY, surfaceY - 4); sy--) {
				BlockState st = block(lx, sy, lz);
				if (st == null) break;
				BlockInfo si = BlockInfo.of(st);
				if (si.kind() == BlockInfo.Kind.SOLID && si.material() != Material.FOLIAGE) {
					subMat = si.material() == Material.GRASS ? Material.DIRT : si.material();
					subColor = si.tint() == BlockInfo.Tint.NONE ? si.color() : 0;
					break;
				}
			}

			// Trees: the canopy is a floating volume; record the real ground under it.
			if (waterTop == Integer.MIN_VALUE && surfaceInfo.tree()) {
				for (int gy = surfaceY - 1; gy >= Math.max(minY, surfaceY - 40); gy--) {
					BlockState st = block(lx, gy, lz);
					if (st == null) continue;
					BlockInfo gi = BlockInfo.of(st);
					if (gi.kind() == BlockInfo.Kind.AIR || gi.kind() == BlockInfo.Kind.DECORATION || gi.tree()) continue;
					int groundColor = gi.kind() == BlockInfo.Kind.WATER ? 0x3F76E4 : tinted(gi, lx, gy, lz, wx, wz);
					Material groundMat = gi.kind() == BlockInfo.Kind.WATER ? Material.WATER : gi.material();
					out.set(lx, lz, surfaceY + 1, gy + 1, surfMat, surfColor, groundMat, groundColor, groundColor, emission, 0);
					return;
				}
			}

			if (waterTop != Integer.MIN_VALUE) {
				Biome biome = biome(lx, waterTop - 1, lz);
				int water = biome != null ? biome.getWaterColor() : 0x3F76E4;
				out.set(lx, lz, waterTop, surfaceY + 1, Material.WATER, water, subMat, subColor, surfColor, emission, 0);
			} else {
				out.set(lx, lz, surfaceY + 1, surfaceY + 1, surfMat, surfColor, subMat, subColor, surfColor, emission, deco);
			}
		}

		private int tinted(BlockInfo info, int lx, int y, int lz, int wx, int wz) {
			if (info.tint() == BlockInfo.Tint.NONE) return info.color();
			Biome biome = biome(lx, y, lz);
			if (biome == null) return info.color();
			return switch (info.tint()) {
				case GRASS -> biome.getGrassColor(wx, wz);
				case FOLIAGE -> biome.getFoliageColor();
				case WATER -> biome.getWaterColor();
				default -> info.color();
			};
		}
	}

	@SuppressWarnings("unchecked")
	public static Snapshot snapshot(LevelChunk chunk, long time, int ceilingStart) {
		LevelChunkSection[] sections = chunk.getSections();
		PalettedContainer<BlockState>[] states = new PalettedContainer[sections.length];
		PalettedContainer<Holder<Biome>>[] biomes = new PalettedContainer[sections.length];
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s == null) continue;
			biomes[i] = s.getBiomes().copy();
			if (!s.hasOnlyAir()) states[i] = s.getStates().copy();
		}
		int[] surface = new int[256];
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				surface[ChunkColumns.index(lx, lz)] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) + 1;
			}
		}
		return new Snapshot(chunk.getPos().x(), chunk.getPos().z(), chunk.getMinY(), states, biomes, surface, time, ceilingStart);
	}
}
