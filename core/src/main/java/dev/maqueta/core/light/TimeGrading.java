package dev.maqueta.core.light;

/**
 * Day/night without re-baking (design 3.4): shadows stay where they are, only the colours
 * of the four light terms change with world time and weather.
 */
public final class TimeGrading {
	public enum Dimension { OVERWORLD, NETHER, END }

	public record Grade(float[] sun, float[] ambient, float[] emission, float saturation, float contrast,
						float[] board, float[] boardDeep, float nightness) {
	}

	private record Key(float time, float[] sun, float[] ambient, float emission, float[] board) {
	}

	// dayTime: 0 = sunrise (06:00), 6000 = noon, 12000 = sunset, 18000 = midnight.
	private static final Key[] KEYS = {
		new Key(0, rgb(1.00f, 0.66f, 0.42f, 0.90f), rgb(0.50f, 0.45f, 0.66f, 1f), 0.45f, rgb(0.80f, 0.70f, 0.72f, 1f)),
		new Key(1800, rgb(1.06f, 0.97f, 0.86f, 1.00f), rgb(0.56f, 0.66f, 0.84f, 1f), 0.10f, rgb(0.80f, 0.84f, 0.88f, 1f)),
		new Key(6000, rgb(1.08f, 1.01f, 0.90f, 1.04f), rgb(0.58f, 0.70f, 0.88f, 1f), 0.05f, rgb(0.82f, 0.86f, 0.90f, 1f)),
		new Key(10200, rgb(1.06f, 0.95f, 0.82f, 1.00f), rgb(0.56f, 0.66f, 0.84f, 1f), 0.10f, rgb(0.80f, 0.83f, 0.86f, 1f)),
		new Key(12000, rgb(1.00f, 0.56f, 0.34f, 0.88f), rgb(0.52f, 0.42f, 0.64f, 1f), 0.55f, rgb(0.78f, 0.62f, 0.62f, 1f)),
		new Key(13400, rgb(0.48f, 0.44f, 0.74f, 0.55f), rgb(0.26f, 0.27f, 0.50f, 1f), 0.90f, rgb(0.30f, 0.30f, 0.44f, 1f)),
		new Key(18000, rgb(0.44f, 0.54f, 0.80f, 0.52f), rgb(0.20f, 0.25f, 0.44f, 1f), 1.00f, rgb(0.14f, 0.16f, 0.26f, 1f)),
		new Key(22600, rgb(0.48f, 0.46f, 0.76f, 0.55f), rgb(0.26f, 0.27f, 0.52f, 1f), 0.90f, rgb(0.30f, 0.30f, 0.46f, 1f)),
		new Key(24000, rgb(1.00f, 0.66f, 0.42f, 0.90f), rgb(0.50f, 0.45f, 0.66f, 1f), 0.45f, rgb(0.80f, 0.70f, 0.72f, 1f)),
	};

	private TimeGrading() {
	}

	private static float[] rgb(float r, float g, float b, float intensity) {
		return new float[]{r * intensity, g * intensity, b * intensity};
	}

	public static Grade grade(Dimension dim, long dayTime, float rain, float thunder) {
		switch (dim) {
			case NETHER -> {
				return new Grade(rgb(1.0f, 0.62f, 0.46f, 0.70f), rgb(0.62f, 0.34f, 0.30f, 1f), rgb(1.0f, 0.68f, 0.38f, 0.85f),
					1.05f, 1.05f, rgb(0.30f, 0.12f, 0.12f, 1f), rgb(0.16f, 0.06f, 0.07f, 1f), 0.5f);
			}
			case END -> {
				return new Grade(rgb(0.82f, 0.78f, 1.0f, 0.75f), rgb(0.44f, 0.38f, 0.58f, 1f), rgb(0.9f, 0.7f, 1.0f, 0.6f),
					0.95f, 1.02f, rgb(0.20f, 0.16f, 0.26f, 1f), rgb(0.10f, 0.08f, 0.14f, 1f), 0.6f);
			}
			default -> {
			}
		}
		float t = Math.floorMod(dayTime, 24000L);
		int i = 0;
		while (i < KEYS.length - 2 && KEYS[i + 1].time <= t) i++;
		Key a = KEYS[i], b = KEYS[i + 1];
		float f = (t - a.time) / (b.time - a.time);
		f = f * f * (3 - 2 * f);
		float[] sun = lerp(a.sun, b.sun, f);
		float[] amb = lerp(a.ambient, b.ambient, f);
		float emis = a.emission + (b.emission - a.emission) * f;
		float[] board = lerp(a.board, b.board, f);

		float wet = Math.max(rain, thunder);
		float sunScale = 1f - 0.45f * rain - 0.2f * thunder;
		for (int k = 0; k < 3; k++) {
			sun[k] *= sunScale;
			amb[k] = amb[k] * (1f - 0.15f * wet) + 0.08f * wet;
			board[k] = board[k] * (1f - 0.25f * wet) + 0.1f * wet;
		}
		float sat = 1f - 0.35f * rain - 0.1f * thunder;
		float contrast = 1f - 0.18f * wet;
		float[] emission = {1.0f * emis, 0.74f * emis, 0.42f * emis};
		float[] deep = {board[0] * 0.55f, board[1] * 0.58f, board[2] * 0.66f};
		float night = Math.max(0f, Math.min(1f, (emis - 0.1f) / 0.9f));
		return new Grade(sun, amb, emission, sat, contrast, board, deep, night);
	}

	private static float[] lerp(float[] a, float[] b, float t) {
		return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
	}

	/** "HH:MM" for a world day time (0 = 06:00). */
	public static String clock(long dayTime, boolean twelveHour) {
		long t = Math.floorMod(dayTime + 6000L, 24000L);
		int hours = (int) (t / 1000);
		int minutes = (int) ((t % 1000) * 60 / 1000);
		if (!twelveHour) return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, minutes);
		int h12 = hours % 12 == 0 ? 12 : hours % 12;
		return String.format(java.util.Locale.ROOT, "%d:%02d %s", h12, minutes, hours < 12 ? "AM" : "PM");
	}
}
