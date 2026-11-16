package dev.maqueta.client.world;

import dev.maqueta.client.render.TileAtlas;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ChunkVoxels;
import dev.maqueta.core.Material;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Two-phase capture (design 7.2):
 * <ol>
 *   <li>{@link #snapshot} runs on the main thread and only copies the paletted section
 *       containers, surface heights and neighbour sections.</li>
 *   <li>{@link Snapshot#process} runs on a worker and turns the copy into layered
 *       {@link ChunkColumns}: every run of blocks from the top down to the natural ground,
 *       with the air between them kept as air.</li>
 * </ol>
 */
public final class ChunkCapture {
	private static final int MAX_SCAN = 192;
	private static final int MAX_RUNS = 96;
	private static final int[][] SIDES = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

	private ChunkCapture() {
	}

	public record Snapshot(int chunkX, int chunkZ, int minY, PalettedContainer<BlockState>[] states,
						   PalettedContainer<Holder<Biome>>[] biomes, int[] surface, long time, int ceilingStart, int[] neighborTop,
						   long[] neighborOpen) {

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

		/**
		 * The 3D shell of the chunk, built the way Minecraft decides what to draw: an occupancy
		 * grid of the visible band and the identity of every block that touches a non-opaque cell.
		 * At chunk borders the neighbour's surface height decides; an unloaded neighbour gives a
		 * straight wall (the edge of the explored world).
		 */
		private void buildVoxels(ChunkColumns out) {
			int lowFloor = Integer.MAX_VALUE, highTop = Integer.MIN_VALUE;
			for (int i = 0; i < ChunkColumns.AREA; i++) {
				if (!out.isKnown(i)) continue;
				lowFloor = Math.min(lowFloor, out.floor[i]);
				highTop = Math.max(highTop, out.top[i]);
			}
			if (highTop == Integer.MIN_VALUE) return;
			// The band reaches down to the lowest neighbouring ground too: a cliff above a valley
			// must keep its face, or the valley looks straight through the mountain.
			int lowSide = lowFloor;
			for (int t : neighborTop) if (t != Integer.MIN_VALUE) lowSide = Math.min(lowSide, t);
			int yMin = Math.max(minY, Math.max(lowFloor - 96, lowSide - 4) - 8);
			int yMax = Math.min(minY + states.length * 16, highTop + 1);
			if (yMax <= yMin) return;
			ChunkVoxels v = new ChunkVoxels(yMin, yMax - yMin);
			int h = yMax - yMin;
			// kind per cell: 0 air/plant, 1 opaque, 2 water, 3 shaped
			byte[] kind = new byte[h * 256];
			for (int y = yMin; y < yMax; y++) {
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						if (ceilingStart != Integer.MIN_VALUE) {
							// Under a roof: what lies above this column's own surface is the roof
							// mass, lifted away so the inside reads like an open model.
							int ci = ChunkColumns.index(lx, lz);
							if (!out.isKnown(ci) || y >= out.top[ci]) continue;
						}
						BlockState st = block(lx, y, lz);
						if (st == null) continue;
						BlockInfo info = BlockInfo.of(st);
						if (info.kind() == BlockInfo.Kind.AIR) continue;
						int c = ChunkVoxels.cell(lx, y - yMin, lz);
						if (info.kind() == BlockInfo.Kind.WATER) {
							kind[c] = 2;
							v.setWater(lx, y, lz);
							continue;
						}
						BlockShape shape = BlockShape.of(st, info);
						if (shape.plantLike()) continue;
						if (shape.boxes() != null && shape.boxes().length == 0 && info.material() != Material.LAVA) continue;
						if (shape.full() && info.kind() == BlockInfo.Kind.SOLID) {
							kind[c] = 1;
							v.setOpaque(lx, y, lz);
						} else {
							kind[c] = 3;
						}
					}
				}
			}
			Map<BlockState, Integer> shellEntries = new IdentityHashMap<>();
			for (int y = yMin; y < yMax; y++) {
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						int c = ChunkVoxels.cell(lx, y - yMin, lz);
						int k = kind[c];
						if (k == 0) continue;
						boolean show;
						if (k == 3) {
							show = true;
						} else if (k == 2) {
							show = y + 1 >= yMax || kind[c + 256] == 0 || kind[c + 256] == 3
								|| sideAir(kind, lx, y, lz, yMin, yMax, true);
						} else {
							// Visible when anything around it is not opaque: open sky above, an
							// overhang's air below, or a side that opens to air.
							show = y + 1 >= yMax || kind[c + 256] != 1
								|| (y > yMin && kind[c - 256] != 1)
								|| sideAir(kind, lx, y, lz, yMin, yMax, false)
								// Under a wall, trunk or cliff block (opaque above, open at its sides):
								// kept so the see-through cutaway can show a cut floor here, not a hole.
								|| (y + 2 < yMax && sideAir(kind, lx, y + 1, lz, yMin, yMax, false));
						}
						if (!show) continue;
						BlockState state = block(lx, y, lz);
						int entry = shellEntries.computeIfAbsent(state, st -> shellEntry(v, st, k == 2));
						v.addShell(lx, y, lz, entry);
					}
				}
			}
			for (int i = 0; i < 256; i++) {
				int lx = i & 15, lz = i >> 4;
				int wx = (chunkX << 4) + lx, wz = (chunkZ << 4) + lz;
				Biome b = biome(lx, out.isKnown(i) ? out.floor[i] : minY + 64, lz);
				if (b == null) continue;
				v.grassTint[i] = b.getGrassColor(wx, wz) & 0xFFFFFF; // ARGB in 26.x: the alpha byte made it negative (= "untinted")
				v.foliageTint[i] = b.getFoliageColor() & 0xFFFFFF;
				v.waterTint[i] = b.getWaterColor() & 0xFFFFFF;
			}
			out.voxels = v;
		}

		private static int shellEntry(ChunkVoxels voxels, BlockState state, boolean water) {
			BlockInfo info = BlockInfo.of(state);
			String key = water ? "minecraft:water" : BlockShape.of(state, info).key();
			Material material = water ? Material.WATER : (info.snowCover() ? Material.SNOW : info.material());
			int tint = water ? ChunkVoxels.TINT_WATER : switch (info.tint()) {
				case GRASS -> ChunkVoxels.TINT_GRASS;
				case FOLIAGE -> ChunkVoxels.TINT_FOLIAGE;
				case WATER -> ChunkVoxels.TINT_WATER;
				case FIXED -> ChunkVoxels.TINT_FIXED;
				default -> ChunkVoxels.TINT_NONE;
			};
			int base = water ? 0xFFFFFF : (info.id().isEmpty() ? -1 : TileAtlas.get().color(info.id(), true));
			if (base < 0) base = info.color();
			return voxels.entryIndex(new ChunkVoxels.Entry(key, material, base, tint, info.color()));
		}

		/** Is any side neighbour open? Outside the chunk, the neighbour's surface height decides. */
		private boolean sideAir(byte[] kind, int lx, int y, int lz, int yMin, int yMax, boolean forWater) {
			for (int side = 0; side < SIDES.length; side++) {
				int[] s = SIDES[side];
				int nx = lx + s[0], nz = lz + s[1];
				if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
					int along = s[0] != 0 ? lz : lx;
					int nTop = neighborTop[side * 16 + along];
					if (nTop == Integer.MIN_VALUE || y >= nTop) return true;
					// The neighbour's real blocks: a cave, overhang or Nether hall next to the
					// border opens this face even far below the neighbour's surface.
					if (neighborOpen != null) {
						int h = states.length * 16;
						long bit = (long) (side * 16 + along) * h + (y - minY);
						if (y >= minY && y - minY < h && (neighborOpen[(int) (bit >>> 6)] & (1L << (bit & 63))) != 0) return true;
					}
					continue;
				}
				int nk = kind[ChunkVoxels.cell(nx, y - yMin, nz)];
				if (forWater ? (nk == 0 || nk == 3) : nk != 1) return true;
			}
			return false;
		}

		public ChunkColumns process() {
			ChunkColumns out = new ChunkColumns(chunkX, chunkZ);
			out.capturedAt = time;
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					column(out, lx, lz);
				}
			}
			buildVoxels(out);
			out.compact();
			return out;
		}

		/** Pending run being accumulated while scanning down. */
		private static final class Run {
			int top = Integer.MIN_VALUE, bottom;
			String key;
			Material mat;
			int color;
			boolean water;
		}

		private void column(ChunkColumns out, int lx, int lz) {
			int i = ChunkColumns.index(lx, lz);
			int wx = (chunkX << 4) + lx, wz = (chunkZ << 4) + lz;
			int y = surface[i] - 1;
			if (ceilingStart != Integer.MIN_VALUE) {
				// Dimensions with a roof (Nether): the roof ends at the first real hollow below it.
				final int clx = lx, clz = lz;
				y = insideStart(yy -> block(clx, yy, clz), Math.min(y, ceilingStart), minY);
			}
			int bottom = Math.max(minY, y - MAX_SCAN);
			out.beginRuns(i);

			int emission = 0;
			int deco = 0;
			int topY = Integer.MIN_VALUE;
			Material topMat = Material.NONE;
			int topColor = 0;
			String topName = "";
			int runsAdded = 0;
			Run run = new Run();
			BlockInfo groundInfo = null;
			int groundY = Integer.MIN_VALUE;

			for (; y >= bottom; y--) {
				BlockState st = block(lx, y, lz);
				if (st == null) {
					runsAdded += flush(out, run);
					continue;
				}
				BlockInfo info = BlockInfo.of(st);
				if (info.kind() == BlockInfo.Kind.AIR) {
					runsAdded += flush(out, run);
					continue;
				}
				BlockShape shape = BlockShape.of(st, info);
				// Only light near the visible top counts: a lava sea 80 blocks down must not
				// make the whole column glow (the Nether turned neon red).
				if (topY == Integer.MIN_VALUE || y >= topY - 3) emission = Math.max(emission, info.emission());
				if (shape.plantLike()) {
					// Flowers, grass tufts, crops: a touch of colour on the ground, no geometry.
					if (deco == 0) deco = tinted(info, lx, y, lz, wx, wz);
					runsAdded += flush(out, run);
					continue;
				}
				if (info.kind() != BlockInfo.Kind.WATER && info.material() != Material.LAVA && shape.boxes() != null && shape.boxes().length == 0) {
					runsAdded += flush(out, run);
					continue; // no outline at all (light blocks, structure voids)
				}
				if ((shape.ground() && solidBelow(lx, y, lz)) || runsAdded >= MAX_RUNS) {
					runsAdded += flush(out, run);
					groundInfo = info;
					groundY = y;
					break;
				}
				boolean water = info.kind() == BlockInfo.Kind.WATER;
				String key = water ? "minecraft:water" : shape.key();
				Material mat = water ? Material.WATER : (info.snowCover() ? Material.SNOW : info.material());
				int color = water ? waterColor(lx, y, lz) : faceColor(info, lx, y, lz, wx, wz);
				if (topY == Integer.MIN_VALUE) {
					topY = y + 1;
					topMat = mat;
					topColor = color;
					topName = key;
				}
				// Shaped blocks are single-block runs; full blocks of the same kind merge.
				if (run.top != Integer.MIN_VALUE && run.key.equals(key) && run.bottom == y + 1 && shape.full()) {
					run.bottom = y;
				} else {
					runsAdded += flush(out, run);
					run.top = y + 1;
					run.bottom = y;
					run.key = key;
					run.mat = mat;
					run.color = color;
					run.water = water;
					if (!shape.full()) runsAdded += flush(out, run);
				}
			}
			runsAdded += flush(out, run);

			if (groundInfo == null) {
				if (topY == Integer.MIN_VALUE) {
					out.top[i] = ChunkColumns.UNKNOWN; // nothing seen in this column
					return;
				}
				groundY = bottom;
				BlockState st = block(lx, bottom, lz);
				groundInfo = st == null ? null : BlockInfo.of(st);
				if (groundInfo == null || groundInfo.kind() != BlockInfo.Kind.SOLID) {
					groundInfo = new BlockInfo(BlockInfo.Kind.SOLID, Material.STONE, 0x7F7F7F, BlockInfo.Tint.NONE, 0, false, false, "minecraft:stone");
				}
			}
			int floor = groundY + 1;
			int groundColor = faceColor(groundInfo, lx, groundY, lz, wx, wz);
			Material groundMat = groundInfo.material();
			if (topY == Integer.MIN_VALUE) {
				topY = floor;
				topMat = groundMat;
				topColor = groundColor;
				topName = groundInfo.id();
			}

			// What a cliff shows below the ground surface.
			Material subMat = groundMat.subsurface();
			int subColor = 0;
			String subName = "";
			for (int sy = groundY - 1; sy >= Math.max(minY, groundY - 4); sy--) {
				BlockState st = block(lx, sy, lz);
				if (st == null) break;
				BlockInfo si = BlockInfo.of(st);
				if (si.kind() == BlockInfo.Kind.SOLID && !si.tree()) {
					subMat = si.material() == Material.GRASS ? Material.DIRT : si.material();
					subName = si.material() == Material.GRASS ? "minecraft:dirt" : si.id();
					subColor = faceColor(si, lx, sy, lz, wx, wz);
					break;
				}
			}

			out.top[i] = (short) topY;
			out.floor[i] = (short) floor;
			out.material[i] = (byte) topMat.ordinal();
			out.color[i] = topColor & 0xFFFFFF;
			out.groundMaterial[i] = (byte) groundMat.ordinal();
			out.floorColor[i] = groundColor & 0xFFFFFF;
			out.subMaterial[i] = (byte) subMat.ordinal();
			out.subColor[i] = subColor & 0xFFFFFF;
			out.emission[i] = (byte) emission;
			out.decoColor[i] = deco;
			int flags = groundInfo.id().contains("path") || groundInfo.id().contains("farmland") ? ChunkColumns.FLAG_PATH : 0;
			out.setNames(lx, lz, topName, subName, groundInfo.id(), biomeName(lx, floor, lz), flags);
		}

		/**
		 * Natural ground must rest on more ground: four full solid blocks right below. A
		 * floating sandstone ledge, a stone tower on stilts or a mangrove root is not ground,
		 * so the air under it stays air.
		 */
		private boolean solidBelow(int lx, int y, int lz) {
			for (int k = 1; k <= 4; k++) {
				if (y - k < minY) return true;
				BlockState st = block(lx, y - k, lz);
				if (st == null) return false;
				BlockInfo bi = BlockInfo.of(st);
				if (bi.kind() != BlockInfo.Kind.SOLID || bi.tree() || !BlockShape.of(st, bi).full()) return false;
			}
			return true;
		}

		private static int flush(ChunkColumns out, Run run) {
			if (run.top == Integer.MIN_VALUE) return 0;
			out.addRun(run.top, run.bottom, out.blockIndex(run.key), run.mat, run.color);
			run.top = Integer.MIN_VALUE;
			return 1;
		}

		private int waterColor(int lx, int y, int lz) {
			Biome biome = biome(lx, y, lz);
			return biome != null ? biome.getWaterColor() : 0x3F76E4;
		}

		/**
		 * The colour a face really has: the texture's own average for most blocks; for
		 * biome-tinted blocks (grass, leaves, vines) the biome colour multiplied by the
		 * grey texture's brightness, exactly like the game does. That is what keeps plains
		 * grass from turning into Minecraft-alpha lime.
		 */
		private int faceColor(BlockInfo info, int lx, int y, int lz, int wx, int wz) {
			int avg = info.id().isEmpty() ? -1 : TileAtlas.get().color(info.id(), true);
			if (info.tint() == BlockInfo.Tint.NONE) {
				return avg >= 0 ? avg : info.color();
			}
			int tint = tinted(info, lx, y, lz, wx, wz);
			if (avg < 0) return tint;
			float lum = (((avg >> 16) & 0xFF) * 0.299f + ((avg >> 8) & 0xFF) * 0.587f + (avg & 0xFF) * 0.114f) / 255f;
			lum = Math.min(1f, lum * 1.08f);
			int r = Math.round(((tint >> 16) & 0xFF) * lum), g = Math.round(((tint >> 8) & 0xFF) * lum), b = Math.round((tint & 0xFF) * lum);
			return (r << 16) | (g << 8) | b;
		}

		private String biomeName(int lx, int y, int lz) {
			int s = Math.max(0, Math.min(biomes.length - 1, (y - minY) >> 4));
			PalettedContainer<Holder<Biome>> b = biomes[s];
			if (b == null) return "";
			return b.get(lx >> 2, ((y - minY) & 15) >> 2, lz >> 2).unwrapKey().map(k -> k.identifier().toString()).orElse("");
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

	/** Consecutive air blocks that mark the end of a roof (smaller pockets belong to the roof mass). */
	static final int ROOF_HOLLOW = 4;

	/**
	 * Under a bedrock roof: the first y of the inside, scanning down from {@code from}. The roof
	 * only ends at a real hollow ({@value #ROOF_HOLLOW} air blocks in a row); small pockets inside
	 * the roof mass and hanging plants (weeping vines) do not end it. Returns minY if the column
	 * is solid all the way down. The same rule is used for neighbour chunks, so borders agree.
	 */
	static int insideStart(java.util.function.IntFunction<BlockState> at, int from, int minY) {
		int run = 0, runTop = from, firstAir = Integer.MIN_VALUE;
		for (int y = from; y > minY; y--) {
			BlockState st = at.apply(y);
			BlockInfo.Kind k = st == null ? BlockInfo.Kind.AIR : BlockInfo.of(st).kind();
			if (k == BlockInfo.Kind.AIR) {
				if (run == 0) runTop = y;
				if (firstAir == Integer.MIN_VALUE) firstAir = y;
				if (++run >= ROOF_HOLLOW) return runTop;
			} else if (k != BlockInfo.Kind.DECORATION) {
				run = 0; // solid (or fluid): still inside the roof mass
			}
		}
		// No real hollow in this column (a wall between halls): the old rule, so it is not a hole.
		return firstAir != Integer.MIN_VALUE ? firstAir : minY;
	}

	/** First y at or below {@code start} holding a visible block (the column's inside top). */
	static int firstBlockBelow(java.util.function.IntFunction<BlockState> at, int start, int minY) {
		for (int y = start; y > minY; y--) {
			BlockState st = at.apply(y);
			if (st != null && BlockInfo.of(st).kind() != BlockInfo.Kind.AIR) return y;
		}
		return minY;
	}

	/** One immutable copy provides the inputs for both layers of a roofed dimension. */
	static record Capture(int chunkX, int chunkZ, int minY, PalettedContainer<BlockState>[] states,
						  PalettedContainer<Holder<Biome>>[] biomes, int[] surface, long time, Border[] borders) {
		ChunkColumns process(int ceilingStart) {
			int height = states.length * 16;
			int[] neighborTop = new int[64];
			Arrays.fill(neighborTop, Integer.MIN_VALUE);
			long[] neighborOpen = new long[height];
			for (int side = 0; side < borders.length; side++) {
				Border border = borders[side];
				if (border == null) continue;
				for (int k = 0; k < 16; k++) {
					int x = border.x < 0 ? k : border.x;
					int z = border.z < 0 ? k : border.z;
					int cutFrom = Integer.MAX_VALUE;
					if (ceilingStart != Integer.MIN_VALUE) {
						java.util.function.IntFunction<BlockState> at = y -> border.block(x, y, z);
						int start = insideStart(at, Math.min(ceilingStart, border.maxY), border.minY);
						cutFrom = firstBlockBelow(at, start, border.minY) + 1;
					}
					int base = (side * 16 + k) * height;
					int length = Math.min(height, border.states.length * 16);
					for (int i = 0; i < length; i++) {
						PalettedContainer<BlockState> section = border.states[i >> 4];
						boolean open = minY + i >= cutFrom || section == null;
						if (!open) {
							BlockState state = section.get(x, i & 15, z);
							BlockInfo info = BlockInfo.of(state);
							open = info.kind() != BlockInfo.Kind.SOLID || !BlockShape.of(state, info).full();
						}
						if (open) neighborOpen[(base + i) >>> 6] |= 1L << ((base + i) & 63);
					}
					int y = border.surface[k];
					int stop = Math.max(border.minY, y - 64);
					while (y > stop) {
						BlockState state = border.block(x, y, z);
						if (state != null && !state.isAir() && !state.is(BlockTags.LEAVES) && state.isSolidRender()) break;
						y--;
					}
					neighborTop[side * 16 + k] = y + 1;
				}
			}
			return new Snapshot(chunkX, chunkZ, minY, states, biomes, surface, time, ceilingStart,
				neighborTop, neighborOpen).process();
		}
	}

	private record Border(int x, int z, int minY, int maxY, PalettedContainer<BlockState>[] states, int[] surface) {
		BlockState block(int x, int y, int z) {
			int offset = y - minY;
			int index = offset >> 4;
			if (index < 0 || index >= states.length || states[index] == null) return null;
			return states[index].get(x, offset & 15, z);
		}
	}

	/** Section copies are shared by adjacent captures in the same client tick. */
	@SuppressWarnings("unchecked")
	static Capture snapshot(ClientLevel level, LevelChunk chunk, long time, Map<Long, PalettedContainer<BlockState>[]> copies) {
		LevelChunkSection[] sections = chunk.getSections();
		PalettedContainer<BlockState>[] states = copyStates(chunk, copies);
		PalettedContainer<Holder<Biome>>[] biomes = new PalettedContainer[sections.length];
		for (int i = 0; i < sections.length; i++) {
			if (sections[i] != null) biomes[i] = sections[i].getBiomes().copy();
		}
		int[] surface = new int[256];
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				surface[ChunkColumns.index(lx, lz)] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) + 1;
			}
		}
		int cx = chunk.getPos().x(), cz = chunk.getPos().z();
		Border[] borders = {
			copyBorder(level, cx - 1, cz, 15, -1, copies),
			copyBorder(level, cx + 1, cz, 0, -1, copies),
			copyBorder(level, cx, cz - 1, -1, 15, copies),
			copyBorder(level, cx, cz + 1, -1, 0, copies)
		};
		return new Capture(cx, cz, chunk.getMinY(), states, biomes, surface, time, borders);
	}

	@SuppressWarnings("unchecked")
	private static PalettedContainer<BlockState>[] copyStates(LevelChunk chunk, Map<Long, PalettedContainer<BlockState>[]> copies) {
		long key = ChunkColumns.key(chunk.getPos().x(), chunk.getPos().z());
		return copies.computeIfAbsent(key, ignored -> {
			LevelChunkSection[] sections = chunk.getSections();
			PalettedContainer<BlockState>[] states = new PalettedContainer[sections.length];
			for (int i = 0; i < sections.length; i++) {
				LevelChunkSection section = sections[i];
				if (section != null && !section.hasOnlyAir()) states[i] = section.getStates().copy();
			}
			return states;
		});
	}

	private static Border copyBorder(ClientLevel level, int cx, int cz, int x, int z,
									 Map<Long, PalettedContainer<BlockState>[]> copies) {
		LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
		if (chunk == null) return null;
		int[] surface = new int[16];
		for (int k = 0; k < 16; k++) {
			surface[k] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x < 0 ? k : x, z < 0 ? k : z);
		}
		return new Border(x, z, chunk.getMinY(), chunk.getMaxY(), copyStates(chunk, copies), surface);
	}
}
