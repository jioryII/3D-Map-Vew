package dev.maqueta.core.marker;

import java.util.Locale;

/**
 * A named point. Stored one per line in a human-readable file so players can share them:
 * <pre>name;x;y;z;RRGGBB;dimension;flags</pre>
 */
public final class Waypoint {
	public static final int FLAG_DEATH = 1;
	public static final int FLAG_HIDDEN = 2;
	/** Received from another player through chat. */
	public static final int FLAG_SHARED = 4;

	public String name;
	public int x, y, z;
	public int color;
	public String dimension;
	public int flags;

	public Waypoint(String name, int x, int y, int z, int color, String dimension, int flags) {
		this.name = name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.color = color & 0xFFFFFF;
		this.dimension = dimension;
		this.flags = flags;
	}

	public boolean isDeath() {
		return (flags & FLAG_DEATH) != 0;
	}

	public boolean isHidden() {
		return (flags & FLAG_HIDDEN) != 0;
	}

	public String initial() {
		return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
	}

	public String serialize() {
		return escape(name) + ";" + x + ";" + y + ";" + z + ";" + String.format(Locale.ROOT, "%06X", color) + ";" + dimension + ";" + flags;
	}

	public static Waypoint parse(String line) {
		String[] p = line.split(";", -1);
		if (p.length < 6) return null;
		try {
			int flags = p.length > 6 ? Integer.parseInt(p[6].trim()) : 0;
			return new Waypoint(unescape(p[0]), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
				Integer.parseInt(p[4].trim(), 16), p[5].trim(), flags);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Chat share format: [maqueta:name@x,y,z@dimension] */
	public String shareText() {
		return "[maqueta:" + name.replace("]", "") + "@" + x + "," + y + "," + z + "@" + dimension + "]";
	}

	public static Waypoint fromShare(String text, int color) {
		int s = text.indexOf("[maqueta:");
		if (s < 0) return null;
		int e = text.indexOf(']', s);
		if (e < 0) return null;
		String[] p = text.substring(s + 9, e).split("@");
		if (p.length < 3) return null;
		String[] c = p[1].split(",");
		if (c.length != 3) return null;
		try {
			return new Waypoint(p[0], Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), color, p[2], 0);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	public double distanceTo(double px, double py, double pz) {
		double dx = x + 0.5 - px, dy = y - py, dz = z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace(";", "\\s");
	}

	private static String unescape(String s) {
		return s.replace("\\s", ";").replace("\\\\", "\\");
	}

	/** Pleasant waypoint colours that read on both the light and the dark board. */
	public static final int[] COLORS = {0xE8574A, 0xF2A33A, 0xF2D64B, 0x6CC56B, 0x3FB6C9, 0x4F7FE0, 0x9B6BE0, 0xE06BB4};
}
