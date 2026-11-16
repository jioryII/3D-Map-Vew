package dev.maqueta.client.world;

import dev.maqueta.core.marker.Waypoint;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sharing points through chat in the formats other map mods already understand, so a
 * Maqueta player and a Xaero's or JourneyMap player can send each other locations.
 * <ul>
 *   <li>Xaero's: {@code xaero-waypoint:Name:N:x:y:z:color:false:0:Internal-overworld-waypoints}</li>
 *   <li>JourneyMap: {@code [name:"Name", x:1, y:2, z:3, dim:minecraft:overworld]}</li>
 *   <li>Maqueta: {@code [maqueta:Name@x,y,z@minecraft:overworld]}</li>
 * </ul>
 */
public final class WaypointShare {
	private static final Pattern XAERO = Pattern.compile("xaero-waypoint:([^:]*):([^:]*):(-?\\d+):(~|-?\\d+):(-?\\d+):(\\d+):[^:]*:[^:]*:Internal[-_]([a-z0-9_-]+?)[-_]waypoints");
	private static final Pattern JOURNEY = Pattern.compile("\\[(?:name:\"([^\"]*)\",\\s*)?x:\\s*(-?\\d+),\\s*y:\\s*(-?\\d+),\\s*z:\\s*(-?\\d+)(?:,\\s*dim:\\s*([a-z0-9_:./-]+))?]");

	private WaypointShare() {
	}

	public static String toChat(Waypoint w) {
		String name = w.name.replace(":", " ").trim();
		if (name.isEmpty()) name = "Point";
		String initial = name.substring(0, 1).toUpperCase(Locale.ROOT);
		String dim = switch (w.dimension) {
			case "minecraft:the_nether" -> "the-nether";
			case "minecraft:the_end" -> "the-end";
			default -> "overworld";
		};
		return "xaero-waypoint:" + name + ":" + initial + ":" + w.x + ":" + w.y + ":" + w.z + ":" + xaeroColor(w.color)
			+ ":false:0:Internal-" + dim + "-waypoints";
	}

	/** Parses any supported share format from a chat line; null if there is none. */
	public static Waypoint fromChat(String text, String currentDimension) {
		Matcher m = XAERO.matcher(text);
		if (m.find()) {
			int y = m.group(4).equals("~") ? 64 : Integer.parseInt(m.group(4));
			String dim = switch (m.group(7)) {
				case "the-nether", "the_nether", "nether" -> "minecraft:the_nether";
				case "the-end", "the_end", "end" -> "minecraft:the_end";
				default -> "minecraft:overworld";
			};
			return new Waypoint(m.group(1), Integer.parseInt(m.group(3)), y, Integer.parseInt(m.group(5)),
				Waypoint.COLORS[Integer.parseInt(m.group(6)) % Waypoint.COLORS.length], dim, Waypoint.FLAG_SHARED);
		}
		Waypoint ours = Waypoint.fromShare(text, 0xF2D64B);
		if (ours != null) {
			ours.flags |= Waypoint.FLAG_SHARED;
			return ours;
		}
		m = JOURNEY.matcher(text);
		if (m.find()) {
			String name = m.group(1) == null ? m.group(2) + ", " + m.group(4) : m.group(1);
			String dim = m.group(5) == null ? currentDimension : m.group(5);
			return new Waypoint(name, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
				0x3FB6C9, dim, Waypoint.FLAG_SHARED);
		}
		return null;
	}

	/** Nearest of Xaero's 16 chat colours. */
	private static int xaeroColor(int rgb) {
		int[] palette = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
		int best = 0;
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < palette.length; i++) {
			long dr = ((rgb >> 16) & 0xFF) - ((palette[i] >> 16) & 0xFF);
			long dg = ((rgb >> 8) & 0xFF) - ((palette[i] >> 8) & 0xFF);
			long db = (rgb & 0xFF) - (palette[i] & 0xFF);
			long d = dr * dr + dg * dg + db * db;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}
}
