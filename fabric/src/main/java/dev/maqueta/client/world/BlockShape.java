package dev.maqueta.client.world;

import dev.maqueta.core.Material;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shape knowledge per block state: whether it is a full cube, its boxes when it is not
 * (stairs, slabs, carpets, torches, lanterns, vines, bamboo, fence posts…), whether it is a
 * small plant that should only tint the ground, and whether it counts as natural ground.
 */
public record BlockShape(String key, float[][] boxes, boolean plantLike, boolean ground) {
	private static final Map<BlockState, BlockShape> BY_STATE = new ConcurrentHashMap<>();
	private static final Map<String, float[][]> BY_KEY = new ConcurrentHashMap<>();
	private static final Set<Material> GROUND = EnumSet.of(Material.GRASS, Material.DIRT, Material.STONE, Material.SAND,
		Material.GRAVEL, Material.SNOW, Material.CLAY, Material.NETHER, Material.END, Material.ICE);
	private static final float[][] FULL = new float[0][];
	private static final float[][] FULL_MARK = new float[0][];

	public boolean full() {
		return boxes == null;
	}

	public static BlockShape of(BlockState state, BlockInfo info) {
		BlockShape s = BY_STATE.get(state);
		if (s == null) {
			s = compute(state, info);
			BY_STATE.put(state, s);
			BY_KEY.putIfAbsent(s.key, s.boxes == null ? FULL_MARK : s.boxes);
		}
		return s;
	}

	private static BlockShape compute(BlockState state, BlockInfo info) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toLowerCase(Locale.ROOT);
		boolean plant = info.kind() == BlockInfo.Kind.DECORATION && !info.snowCover() && info.emission() == 0
			&& (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.CROPS)
			|| path.contains("grass") || path.contains("fern") || path.contains("bush") || path.contains("sprouts")
			|| path.contains("roots") || path.contains("petals") || path.contains("mushroom") || path.contains("sugar_cane")
			|| path.contains("leaf_litter") || path.contains("wildflowers"));
		float[][] boxes = null;
		try {
			VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (info.kind() == BlockInfo.Kind.WATER || info.material() == Material.LAVA) {
				boxes = null; // fluids have no outline but are drawn as a full water surface
			} else if (shape.isEmpty()) {
				boxes = FULL;
			} else if (shape != Shapes.block() && !isFullCube(shape)) {
				List<AABB> list = shape.toAabbs();
				int n = Math.min(6, list.size());
				boxes = new float[n][];
				for (int k = 0; k < n; k++) {
					AABB a = list.get(k);
					boxes[k] = new float[]{(float) a.minX, (float) a.minY, (float) a.minZ, (float) a.maxX, (float) a.maxY, (float) a.maxZ};
				}
			}
		} catch (Throwable t) {
			boxes = null;
		}
		boolean axial = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS);
		String key = boxes == null && !axial ? info.id() : BlockStateParser.serialize(state);
		boolean ground = boxes == null && info.kind() == BlockInfo.Kind.SOLID && !info.tree() && GROUND.contains(info.material());
		return new BlockShape(key, boxes == FULL ? new float[0][] : boxes, plant, ground);
	}

	private static boolean isFullCube(VoxelShape shape) {
		AABB b = shape.bounds();
		return shape.toAabbs().size() == 1 && b.minX <= 0.001 && b.minY <= 0.001 && b.minZ <= 0.001
			&& b.maxX >= 0.999 && b.maxY >= 0.999 && b.maxZ >= 0.999;
	}

	/** Boxes for a palette key ("minecraft:oak_stairs[facing=east,...]"), or null for a full cube. */
	public static float[][] boxes(String key) {
		if (key == null || key.isEmpty()) return null;
		float[][] cached = BY_KEY.get(key);
		if (cached != null) return cached == FULL_MARK ? null : cached;
		float[][] result;
		try {
			BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, key, false).blockState();
			result = of(state, BlockInfo.of(state)).boxes;
		} catch (Exception e) {
			result = null;
		}
		BY_KEY.put(key, result == null ? FULL_MARK : result);
		return result;
	}

	public static void clearCache() {
		BY_STATE.clear();
		BY_KEY.clear();
	}
}
