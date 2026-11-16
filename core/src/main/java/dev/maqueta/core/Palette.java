package dev.maqueta.core;

import java.util.EnumMap;
import java.util.Map;

/**
 * The curated maqueta palette: one six-step ramp per {@link Material} (120 samples in total).
 * <p>
 * Source colours coming from the game (map colours, biome tints) are never used directly.
 * They are converted to OKLab, matched by lightness against the material ramp and pulled
 * towards it. The result keeps biome variety but always lands inside a harmonious set:
 * saturated mid-tones, cool shadows, warm lights, never pure black or pure white.
 */
public final class Palette {
	/** How much of the source colour survives, per material (0 = ramp only). */
	private static final Map<Material, Float> SOURCE_WEIGHT = new EnumMap<>(Material.class);
	private static final Map<Material, int[]> RAMPS = new EnumMap<>(Material.class);

	static {
		ramp(Material.NONE, 0.30f, 0x3A3F4B, 0x555C6B, 0x737B8A, 0x939BA8, 0xB4BAC4, 0xD5D9DF);
		ramp(Material.GRASS, 0.30f, 0x2F5A2E, 0x3F7A36, 0x56963F, 0x72AE4A, 0x93C460, 0xB8D982);
		ramp(Material.FOLIAGE, 0.26f, 0x1F4A2C, 0x2A6136, 0x377A3F, 0x4C9447, 0x67AD55, 0x8CC66C);
		ramp(Material.PLANT, 0.35f, 0x3C6B2F, 0x4E8538, 0x659E43, 0x82B651, 0xA2CB66, 0xC4DD8A);
		ramp(Material.DIRT, 0.35f, 0x4A3326, 0x5F4231, 0x77543D, 0x8F6A4D, 0xA88462, 0xC2A07D);
		ramp(Material.STONE, 0.30f, 0x4A4E57, 0x5E636C, 0x767B83, 0x8F949A, 0xAAAEB2, 0xC7CACD);
		ramp(Material.SAND, 0.40f, 0x9C8457, 0xB59C69, 0xCBB27D, 0xDCC593, 0xE8D6AA, 0xF2E5C4);
		ramp(Material.GRAVEL, 0.30f, 0x5A5452, 0x706966, 0x88807C, 0x9F9893, 0xB7B1AB, 0xCEC9C3);
		ramp(Material.SNOW, 0.25f, 0xA9B9CF, 0xBCCADC, 0xCDD8E6, 0xDCE4EE, 0xE8EEF4, 0xF1F4F8);
		ramp(Material.ICE, 0.35f, 0x6C93C4, 0x82A6D1, 0x98B8DC, 0xAFC9E5, 0xC6D9EE, 0xDCE8F5);
		ramp(Material.WOOD, 0.55f, 0x4B3222, 0x62432C, 0x7B5738, 0x956D47, 0xAE8659, 0xC6A072);
		ramp(Material.BUILT, 0.75f, 0x4E4A54, 0x6A6470, 0x878090, 0xA49DAA, 0xC1BBC4, 0xDCD8DE);
		ramp(Material.ROOF, 0.65f, 0x6B2E2A, 0x873A31, 0xA04A3A, 0xB75F47, 0xCB7859, 0xDD9573);
		ramp(Material.METAL, 0.50f, 0x55606D, 0x6C7885, 0x85919D, 0x9FAAB5, 0xBAC3CC, 0xD4DBE1);
		ramp(Material.WATER, 0.55f, 0x1C4A7A, 0x245E93, 0x2F74AA, 0x3F8BC0, 0x56A3D2, 0x75BCE2);
		ramp(Material.LAVA, 0.40f, 0xA8361A, 0xC9481E, 0xE2632A, 0xF08436, 0xF7A64C, 0xFBC56E);
		ramp(Material.EMISSIVE, 0.50f, 0xB0782C, 0xCA8F37, 0xDFA645, 0xEEBD57, 0xF6D16E, 0xFBE38E);
		ramp(Material.CLAY, 0.55f, 0x7B4E3E, 0x94604B, 0xAB745B, 0xBF896D, 0xD1A083, 0xE1B89C);
		ramp(Material.NETHER, 0.50f, 0x4A1D22, 0x62262B, 0x7C3235, 0x964441, 0xAE5A50, 0xC47563);
		ramp(Material.END, 0.40f, 0x8D8A63, 0xA3A076, 0xB8B58A, 0xCAC79E, 0xDAD8B3, 0xE8E6C9);
	}

	private Palette() {
	}

	private static void ramp(Material material, float sourceWeight, int... colors) {
		RAMPS.put(material, colors);
		SOURCE_WEIGHT.put(material, sourceWeight);
	}

	public static int sampleCount() {
		return RAMPS.values().stream().mapToInt(r -> r.length).sum();
	}

	public static int[] ramp(Material material) {
		return RAMPS.getOrDefault(material, RAMPS.get(Material.NONE)).clone();
	}

	/**
	 * Stylise a source colour.
	 *
	 * @param material   material family
	 * @param sourceRgb  0xRRGGBB colour reported by the game (0 if unknown)
	 * @param variation  -1..1 deterministic per-position jitter, keeps large areas alive
	 * @return 0xRRGGBB
	 */
	public static int stylize(Material material, int sourceRgb, float variation) {
		int[] ramp = RAMPS.getOrDefault(material, RAMPS.get(Material.NONE));
		float weight = SOURCE_WEIGHT.getOrDefault(material, 0.3f);

		float[] src = OkLab.fromRgb(sourceRgb == 0 ? ramp[ramp.length / 2] : sourceRgb);
		// Pick the ramp position by lightness, then blend in the source chroma.
		float rampPos = clamp((src[0] - 0.30f) / 0.62f, 0f, 1f) * (ramp.length - 1);
		rampPos = clamp(rampPos + variation * 0.55f, 0f, ramp.length - 1);
		int lo = (int) Math.floor(rampPos);
		int hi = Math.min(lo + 1, ramp.length - 1);
		float t = rampPos - lo;
		float[] a = OkLab.fromRgb(ramp[lo]);
		float[] b = OkLab.fromRgb(ramp[hi]);
		float L = lerp(a[0], b[0], t);
		float A = lerp(a[1], b[1], t);
		float B = lerp(a[2], b[2], t);

		L = lerp(L, src[0], weight * 0.6f);
		A = lerp(A, src[1], weight);
		B = lerp(B, src[2], weight);

		// Mid-tone saturation boost, softened at the extremes.
		float mid = 1f - Math.abs(L - 0.62f) * 1.6f;
		float boost = 1f + 0.10f * clamp(mid, 0f, 1f);
		A *= boost;
		B *= boost;

		// Never pure black, never pure white.
		L = clamp(L, 0.22f, 0.95f);
		return OkLab.toRgb(L, A, B);
	}

	/**
	 * Faithful harmonisation: keeps the block's own hue (cherry leaves stay pink, birch stays
	 * birch) and only calms what would shout: a soft knee on very saturated colours, a little
	 * lift in the deepest darks and a cap on the highlights, plus a tiny per-position variation.
	 * Falls back to the curated ramp when the source colour is unknown.
	 */
	public static int harmonize(Material material, int sourceRgb, float variation) {
		if (sourceRgb == 0) return stylize(material, 0, variation);
		float[] lab = OkLab.fromRgb(sourceRgb);
		float L = lab[0], a = lab[1], b = lab[2];
		// Pale foliage (cherry blossom) and flowers keep their tint instead of washing out to white.
		float maxL = material == Material.FOLIAGE || material == Material.PLANT ? 0.84f : 0.93f;
		L = clamp(L + variation * 0.022f, 0.20f, maxL);
		float chroma = (float) Math.sqrt(a * a + b * b);
		if (chroma > 1e-5f) {
			float knee = 0.13f;
			float k = chroma > knee ? knee + (chroma - knee) * 0.7f : chroma;
			k *= 1.08f; // a touch more colour for the animated look
			if (material == Material.FOLIAGE && chroma < 0.09f) k = Math.max(k, chroma * 1.35f);
			a *= k / chroma;
			b *= k / chroma;
		}
		return OkLab.toRgb(L, a, b);
	}

	/** Shade a colour along its own lightness axis (used for cliff bands, water depth, foam). */
	public static int shade(int rgb, float deltaL) {
		float[] lab = OkLab.fromRgb(rgb);
		return OkLab.toRgb(clamp(lab[0] + deltaL, 0.18f, 0.97f), lab[1], lab[2]);
	}

	/** Linear mix of two 0xRRGGBB colours in OKLab. */
	public static int mix(int rgbA, int rgbB, float t) {
		float[] a = OkLab.fromRgb(rgbA);
		float[] b = OkLab.fromRgb(rgbB);
		return OkLab.toRgb(lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t));
	}

	/** Stable hash jitter in -1..1 for a world column. */
	public static float variation(int x, int z) {
		int h = x * 0x45d9f3b ^ z * 0x119de1f3;
		h = (h ^ (h >>> 16)) * 0x45d9f3b;
		h ^= h >>> 16;
		return ((h & 0xFFFF) / 65535f) * 2f - 1f;
	}

	static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/** Minimal OKLab (Björn Ottosson) conversion on 0xRRGGBB integers. */
	public static final class OkLab {
		private OkLab() {
		}

		public static float[] fromRgb(int rgb) {
			float r = toLinear(((rgb >> 16) & 0xFF) / 255f);
			float g = toLinear(((rgb >> 8) & 0xFF) / 255f);
			float b = toLinear((rgb & 0xFF) / 255f);
			float l = (float) Math.cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b);
			float m = (float) Math.cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b);
			float s = (float) Math.cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b);
			return new float[]{
				0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
				1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
				0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
			};
		}

		public static int toRgb(float L, float a, float b) {
			float l = L + 0.3963377774f * a + 0.2158037573f * b;
			float m = L - 0.1055613458f * a - 0.0638541728f * b;
			float s = L - 0.0894841775f * a - 1.2914855480f * b;
			l = l * l * l;
			m = m * m * m;
			s = s * s * s;
			float r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
			float g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
			float bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
			return (to8(fromLinear(r)) << 16) | (to8(fromLinear(g)) << 8) | to8(fromLinear(bl));
		}

		private static float toLinear(float c) {
			return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
		}

		private static float fromLinear(float c) {
			c = clamp(c, 0f, 1f);
			return c <= 0.0031308f ? c * 12.92f : (float) (1.055f * Math.pow(c, 1 / 2.4f) - 0.055f);
		}

		private static int to8(float v) {
			return Math.round(clamp(v, 0f, 1f) * 255f);
		}
	}
}
