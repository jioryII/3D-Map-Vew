package dev.maqueta.core;

/**
 * Coarse material families. Every block state is reduced to one of these plus its
 * source colour; the {@link Palette} decides the final look. Keeping this list small
 * is what makes the maqueta read as one coherent object instead of a texture dump.
 */
public enum Material {
	NONE,
	GRASS,
	FOLIAGE,
	PLANT,
	DIRT,
	STONE,
	SAND,
	GRAVEL,
	SNOW,
	ICE,
	WOOD,
	BUILT,
	ROOF,
	METAL,
	WATER,
	LAVA,
	EMISSIVE,
	CLAY,
	NETHER,
	END;

	private static final Material[] VALUES = values();

	public static Material byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
	}

	public boolean isFluid() {
		return this == WATER || this == LAVA;
	}

	/** Player constructions are never simplified before natural terrain (design 3.2). */
	public boolean isBuilt() {
		return this == BUILT || this == ROOF || this == METAL || this == WOOD || this == EMISSIVE;
	}

	/** Material shown on the vertical cliff faces below a surface of this material. */
	public Material subsurface() {
		return switch (this) {
			case GRASS, PLANT, FOLIAGE -> DIRT;
			case SNOW -> STONE;
			case SAND -> SAND;
			case NETHER -> NETHER;
			case END -> END;
			case BUILT, ROOF, METAL, WOOD, EMISSIVE -> this;
			case WATER -> SAND;
			default -> this;
		};
	}
}
