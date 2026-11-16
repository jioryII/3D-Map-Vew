package dev.maqueta.core.mesh;

/**
 * What the meshers need to know about a block, by name (optionally with block-state
 * properties): its texture tile, its average colour and its real shape. Implementations must
 * be thread-safe because meshers run on worker threads.
 */
public interface TileSource {
	TileSource NONE = (block, top) -> 0;

	/** Tile in the maqueta atlas; 0 means "no texture" (flat stylised colour). */
	int tile(String block, boolean top);

	/** Average colour (0xRRGGBB) of that face's texture, or -1 if unknown. */
	default int color(String block, boolean top) {
		return -1;
	}

	/**
	 * Boxes of a block that is not a full cube, as {x0, y0, z0, x1, y1, z1} in block units
	 * (0..1). {@code null} means a full cube.
	 */
	default float[][] shape(String block) {
		return null;
	}
}
