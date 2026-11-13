package dev.maqueta.client.world;

import dev.maqueta.core.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a block state takes part in the maqueta. Computed once per state and cached; safe to
 * call from worker threads (only immutable block properties and bound tags are read).
 */
public record BlockInfo(Kind kind, Material material, int color, Tint tint, int emission, boolean snowCover, boolean tree) {
	public enum Kind { AIR, DECORATION, WATER, SOLID }

	public enum Tint { NONE, GRASS, FOLIAGE, WATER }

	private static final Map<BlockState, BlockInfo> CACHE = new ConcurrentHashMap<>();
	private static final BlockInfo AIR = new BlockInfo(Kind.AIR, Material.NONE, 0, Tint.NONE, 0, false, false);

	public static BlockInfo of(BlockState state) {
		BlockInfo info = CACHE.get(state);
		if (info == null) {
			info = classify(state);
			CACHE.put(state, info);
		}
		return info;
	}

	public static void clearCache() {
		CACHE.clear();
	}

	private static BlockInfo classify(BlockState state) {
		if (state.isAir()) return AIR;
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toLowerCase(Locale.ROOT);
		int emission = state.getLightEmission();
		MapColor mc;
		try {
			mc = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (Throwable t) {
			mc = MapColor.STONE;
		}
		int col = mc == null ? 0x808080 : mc.col;

		FluidState fluid = state.getFluidState();
		boolean motion = !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		if (!fluid.isEmpty()) {
			if (fluid.is(FluidTags.LAVA)) {
				return new BlockInfo(Kind.SOLID, Material.LAVA, 0xE2632A, Tint.NONE, 15, false, false);
			}
			if (fluid.is(FluidTags.WATER) && !motion) {
				return new BlockInfo(Kind.WATER, Material.WATER, 0x3F76E4, Tint.WATER, emission, false, false);
			}
		}

		if (state.is(BlockTags.LEAVES)) {
			Tint tint = mc == MapColor.PLANT ? Tint.FOLIAGE : Tint.NONE;
			if (state.is(Blocks.SPRUCE_LEAVES)) return new BlockInfo(Kind.SOLID, Material.FOLIAGE, 0x619961, Tint.NONE, emission, false, true);
			if (state.is(Blocks.BIRCH_LEAVES)) return new BlockInfo(Kind.SOLID, Material.FOLIAGE, 0x80A755, Tint.NONE, emission, false, true);
			return new BlockInfo(Kind.SOLID, Material.FOLIAGE, col, tint, emission, false, true);
		}

		if (!motion) {
			if (state.is(Blocks.SNOW)) {
				return new BlockInfo(Kind.DECORATION, Material.SNOW, 0xF4F8FA, Tint.NONE, 0, true, false);
			}
			int deco = flowerColor(path);
			Tint tint = Tint.NONE;
			if (deco == 0) {
				if (emission > 0) {
					deco = 0xFFC864;
				} else if (mc == MapColor.PLANT || mc == MapColor.GRASS) {
					deco = col;
					tint = Tint.GRASS;
				} else {
					deco = col;
				}
			}
			return new BlockInfo(Kind.DECORATION, Material.PLANT, deco, tint, emission, false, false);
		}

		return new BlockInfo(Kind.SOLID, solidMaterial(state, path, mc, emission), col, solidTint(state, mc), emission, false, state.is(BlockTags.LOGS));
	}

	private static Tint solidTint(BlockState state, MapColor mc) {
		if (mc == MapColor.GRASS || state.is(Blocks.GRASS_BLOCK)) return Tint.GRASS;
		return Tint.NONE;
	}

	private static Material solidMaterial(BlockState state, String path, MapColor mc, int emission) {
		if (mc == MapColor.GRASS || state.is(Blocks.GRASS_BLOCK)) return Material.GRASS;
		if (emission >= 10) return Material.EMISSIVE;
		if (path.contains("end_stone") || path.contains("purpur") || path.contains("chorus")) return Material.END;
		if (mc == MapColor.NETHER || mc == MapColor.CRIMSON_NYLIUM || mc == MapColor.WARPED_NYLIUM || mc == MapColor.CRIMSON_STEM
			|| mc == MapColor.WARPED_STEM || mc == MapColor.WARPED_WART_BLOCK || path.contains("netherrack") || path.contains("soul")
			|| path.contains("basalt") || path.contains("blackstone") || path.contains("nylium")) return Material.NETHER;
		if (state.is(BlockTags.STAIRS)) return Material.ROOF;
		if (path.contains("glazed") || path.contains("brick") || path.contains("concrete") || path.contains("wool") || path.contains("glass")
			|| path.contains("quartz") || path.contains("polished") || path.contains("smooth") || path.contains("cut_") || path.contains("chiseled")
			|| path.contains("tiles") || path.contains("bookshelf") || path.contains("wall") || path.contains("fence") || path.contains("door")
			|| state.is(BlockTags.SLABS)) return Material.BUILT;
		if (path.contains("planks") || path.contains("log") || path.contains("wood") || path.contains("stem") || path.contains("hyphae")
			|| mc == MapColor.WOOD || mc == MapColor.PODZOL && path.contains("log")) return Material.WOOD;
		if (mc == MapColor.METAL || mc == MapColor.GOLD || mc == MapColor.DIAMOND || mc == MapColor.EMERALD || mc == MapColor.LAPIS
			|| path.contains("copper") || path.contains("iron") || path.contains("anvil")) return Material.METAL;
		if (path.contains("terracotta")) return Material.CLAY;
		if (state.is(BlockTags.SNOW) || mc == MapColor.SNOW || path.contains("snow")) return Material.SNOW;
		if (state.is(BlockTags.ICE) || mc == MapColor.ICE || path.contains("ice")) return Material.ICE;
		if (path.contains("gravel")) return Material.GRAVEL;
		if (path.contains("clay")) return Material.CLAY;
		if (mc == MapColor.SAND || path.contains("sand")) return Material.SAND;
		if (mc == MapColor.DIRT || mc == MapColor.PODZOL || path.contains("dirt") || path.contains("mud") || path.contains("farmland")
			|| path.contains("path") || path.contains("mycelium")) return Material.DIRT;
		if (mc == MapColor.PLANT || mc == MapColor.COLOR_LIGHT_GREEN || mc == MapColor.COLOR_GREEN || path.contains("moss")
			|| path.contains("cactus") || path.contains("mushroom")) return Material.PLANT;
		if (mc == MapColor.STONE || mc == MapColor.DEEPSLATE || path.contains("stone") || path.contains("andesite") || path.contains("diorite")
			|| path.contains("granite") || path.contains("tuff") || path.contains("calcite") || path.contains("ore") || path.contains("deepslate")) return Material.STONE;
		if (mc == MapColor.WATER) return Material.ICE;
		if (mc == MapColor.FIRE) return Material.LAVA;
		if (mc.id >= MapColor.COLOR_ORANGE.id && mc.id <= MapColor.COLOR_BLACK.id || mc == MapColor.WOOL || mc == MapColor.QUARTZ) return Material.BUILT;
		return Material.STONE;
	}

	/** Flowers have a green map colour in vanilla; give them their petal colour instead. */
	private static int flowerColor(String path) {
		if (path.contains("poppy") || path.contains("rose_bush") || path.contains("red_tulip")) return 0xD8382E;
		if (path.contains("dandelion") || path.contains("sunflower")) return 0xF5D43A;
		if (path.contains("cornflower")) return 0x5A7FE0;
		if (path.contains("allium") || path.contains("lilac")) return 0xB57BDB;
		if (path.contains("azure_bluet") || path.contains("oxeye") || path.contains("lily_of_the_valley") || path.contains("white_tulip")) return 0xEDEDE6;
		if (path.contains("orange_tulip") || path.contains("torchflower")) return 0xF08A2C;
		if (path.contains("pink_tulip") || path.contains("peony") || path.contains("pink_petals") || path.contains("cherry")) return 0xF2A2C0;
		if (path.contains("wither_rose")) return 0x2B2626;
		if (path.contains("blue_orchid")) return 0x3FB6D8;
		return 0;
	}
}
