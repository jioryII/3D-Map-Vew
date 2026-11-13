package dev.maqueta.core.hud;

/** Where the minimap lives on screen. Offsets push away from the anchored edge(s). */
public enum HudAnchor {
	TOP_LEFT(0, 0), TOP_CENTER(1, 0), TOP_RIGHT(2, 0),
	MIDDLE_LEFT(0, 1), MIDDLE_RIGHT(2, 1),
	BOTTOM_LEFT(0, 2), BOTTOM_CENTER(1, 2), BOTTOM_RIGHT(2, 2);

	private final int col;
	private final int row;

	HudAnchor(int col, int row) {
		this.col = col;
		this.row = row;
	}

	/** Top-left corner of a {@code size x size} box anchored on a {@code screenW x screenH} screen. */
	public int[] place(int screenW, int screenH, int width, int height, int offsetX, int offsetY) {
		int x = switch (col) {
			case 0 -> offsetX;
			case 1 -> (screenW - width) / 2 + offsetX;
			default -> screenW - width - offsetX;
		};
		int y = switch (row) {
			case 0 -> offsetY;
			case 1 -> (screenH - height) / 2 + offsetY;
			default -> screenH - height - offsetY;
		};
		return new int[]{x, y};
	}

	public HudAnchor next() {
		HudAnchor[] v = values();
		return v[(ordinal() + 1) % v.length];
	}

	public String key() {
		return "maqueta.anchor." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
