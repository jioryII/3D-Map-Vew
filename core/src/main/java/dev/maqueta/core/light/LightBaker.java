package dev.maqueta.core.light;

import dev.maqueta.core.ChunkColumns;

/**
 * Bakes the four light terms of design 3.3 against a heightfield.
 * <ul>
 *   <li><b>Sun</b>: soft shadow from a single fixed sun (north-west, 35 degrees).</li>
 *   <li><b>Sky</b>: how much open sky a point sees (horizon scan in 8 directions).</li>
 *   <li><b>Contact</b>: corner/crease darkening, computed by the mesher per vertex.</li>
 *   <li><b>Emission</b>: block light from torches, lava, lanterns with a soft falloff.</li>
 * </ul>
 * The sun never moves; time of day only re-grades these terms in the shader.
 */
public final class LightBaker {
	public static final float SUN_ELEVATION_DEG = 35f;
	public static final float SUN_AZIMUTH_DEG = 225f; // from north-west, measured from +X towards +Z

	/** Unit vector pointing towards the sun (x, y, z). */
	public static final float[] SUN_DIR = sunDirection(SUN_AZIMUTH_DEG, SUN_ELEVATION_DEG);

	private static final float[][] SHADOW_RAYS = {
		sunDirection(SUN_AZIMUTH_DEG, SUN_ELEVATION_DEG),
		sunDirection(SUN_AZIMUTH_DEG - 6f, SUN_ELEVATION_DEG + 3f),
		sunDirection(SUN_AZIMUTH_DEG + 6f, SUN_ELEVATION_DEG - 3f),
		sunDirection(SUN_AZIMUTH_DEG + 3f, SUN_ELEVATION_DEG + 4f),
		sunDirection(SUN_AZIMUTH_DEG - 3f, SUN_ELEVATION_DEG - 4f),
	};

	private static final int SKY_DIRS = 8;
	private static final int SKY_RANGE = 20;

	private LightBaker() {
	}

	public static float[] sunDirection(float azimuthDeg, float elevationDeg) {
		double az = Math.toRadians(azimuthDeg);
		double el = Math.toRadians(elevationDeg);
		return new float[]{(float) (Math.cos(az) * Math.cos(el)), (float) Math.sin(el), (float) (Math.sin(az) * Math.cos(el))};
	}

	/** 0 = fully shadowed, 1 = fully lit. Point is in world coordinates. */
	public static float sun(HeightWindow w, float x, float y, float z) {
		int lit = 0;
		int maxDist = HeightWindow.MARGIN - 2;
		for (float[] d : SHADOW_RAYS) {
			float horiz = (float) Math.sqrt(d[0] * d[0] + d[2] * d[2]);
			float sx = d[0] / horiz, sz = d[2] / horiz, rise = d[1] / horiz;
			boolean blocked = false;
			for (float t = 0.8f; t < maxDist; t += 0.7f) {
				int h = w.top((int) Math.floor(x + sx * t), (int) Math.floor(z + sz * t));
				if (h == ChunkColumns.UNKNOWN) continue;
				if (h > y + rise * t + 0.02f) {
					blocked = true;
					break;
				}
				if (y + rise * t > 400) break;
			}
			if (!blocked) lit++;
		}
		return lit / (float) SHADOW_RAYS.length;
	}

	/** Fraction of the sky hemisphere visible from a point (0..1). */
	public static float sky(HeightWindow w, float x, float y, float z) {
		float sum = 0f;
		for (int i = 0; i < SKY_DIRS; i++) {
			double a = (i + 0.5) * Math.PI * 2 / SKY_DIRS;
			float dx = (float) Math.cos(a), dz = (float) Math.sin(a);
			float maxSlope = 0f;
			for (int t = 1; t <= SKY_RANGE; t++) {
				int h = w.top((int) Math.floor(x + dx * t), (int) Math.floor(z + dz * t));
				if (h == ChunkColumns.UNKNOWN) continue;
				float slope = (h - y) / t;
				if (slope > maxSlope) maxSlope = slope;
			}
			sum += 1f - maxSlope / (float) Math.sqrt(1 + maxSlope * maxSlope);
		}
		return sum / SKY_DIRS;
	}

	/** Block light with a soft distance falloff, 0..1. */
	public static float emission(HeightWindow w, int x, int z, int y) {
		float best = 0f;
		for (int dz = -5; dz <= 5; dz++) {
			for (int dx = -5; dx <= 5; dx++) {
				int e = w.emission(x + dx, z + dz);
				if (e <= 0) continue;
				int h = w.top(x + dx, z + dz);
				float dy = h == ChunkColumns.UNKNOWN ? 0 : Math.abs(h - y);
				float dist = (float) Math.sqrt(dx * dx + dz * dz + dy * dy * 0.5f);
				float v = (e - dist * 2.3f) / 15f;
				if (v > best) best = v;
			}
		}
		return Math.min(1f, best);
	}

	/** Vertex ambient occlusion from the classic three-neighbour rule, mapped to a soft 0.5..1 range. */
	public static float vertexAo(boolean side1, boolean side2, boolean corner) {
		int occ = side1 && side2 ? 3 : (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
		return switch (occ) {
			case 0 -> 1f;
			case 1 -> 0.80f;
			case 2 -> 0.64f;
			default -> 0.50f;
		};
	}
}
