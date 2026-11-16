package dev.maqueta.client.hud;

import dev.maqueta.client.render.SceneMarkers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;


/**
 * Small vector-like drawing kit for the map UI. Shapes are rasterised in physical pixels
 * (not GUI units), so circles, rings and needles stay crisp at any GUI scale.
 */
public final class MapUi {
	public static final int PANEL = 0xD0141822;
	public static final int PANEL_LIGHT = 0xE01C2230;
	public static final int TEXT = 0xFFF2F4F8;
	public static final int TEXT_DIM = 0xFFA9B3C4;
	public static final int ACCENT = 0xFF2F8CFF;
	public static final int NORTH = 0xFFF05A4F;

	private MapUi() {
	}

	/** Runs the drawing in physical pixel space centred on a GUI point. */
	private interface PixelPainter {
		void paint(int scale);
	}

	private static void inPixels(GuiGraphicsExtractor g, float x, float y, PixelPainter p) {
		int s = Minecraft.getInstance().getWindow().getGuiScale();
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(1f / s, 1f / s);
		p.paint(s);
		g.pose().popMatrix();
	}

	/** Filled circle of radius r (GUI units) centred at (cx, cy): one blit of a baked badge. */
	public static void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
		Badges.disc(g, cx, cy, r, argb);
	}

	/** Thin ring of outer radius r1 (GUI units). */
	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float r0, float r1, int argb) {
		Badges.ring(g, cx, cy, r1, argb);
	}

	/** Filled triangle (GUI units, relative to the origin point). */
	public static void triangle(GuiGraphicsExtractor g, float ox, float oy, float x0, float y0, float x1, float y1, float x2, float y2, int argb) {
		inPixels(g, ox, oy, s -> {
			float[] xs = {x0 * s, x1 * s, x2 * s}, ys = {y0 * s, y1 * s, y2 * s};
			int minY = (int) Math.floor(Math.min(ys[0], Math.min(ys[1], ys[2])));
			int maxY = (int) Math.ceil(Math.max(ys[0], Math.max(ys[1], ys[2])));
			for (int y = minY; y < maxY; y++) {
				float sy = y + 0.5f;
				float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
				for (int e = 0; e < 3; e++) {
					float ax = xs[e], ay = ys[e], bx = xs[(e + 1) % 3], by = ys[(e + 1) % 3];
					if ((sy >= ay && sy < by) || (sy >= by && sy < ay)) {
						float x = ax + (sy - ay) / (by - ay) * (bx - ax);
						lo = Math.min(lo, x);
						hi = Math.max(hi, x);
					}
				}
				if (hi > lo) g.fill(Math.round(lo), y, Math.round(hi), y + 1, argb);
			}
		});
	}

	/** A thin line segment (GUI units) drawn as a rotated quad made of two triangles. */
	public static void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float width, int argb) {
		float dx = x1 - x0, dy = y1 - y0;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 1e-3f) return;
		float nx = -dy / len * width * 0.5f, ny = dx / len * width * 0.5f;
		triangle(g, 0, 0, x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, argb);
		triangle(g, 0, 0, x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, argb);
	}

	public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
		g.fill(x0 + 1, y0, x1 - 1, y1, argb);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}

	/**
	 * Professional compass: bezel with 24 ticks that turns with the camera, a two-tone needle
	 * that always points north, and the heading underneath. Returns nothing; the caller handles clicks.
	 */
	public static void compass(GuiGraphicsExtractor g, Font font, float cx, float cy, float r, float yawRad) {
		disc(g, cx, cy + 0.6f, r + 1.2f, 0x50000000);
		disc(g, cx, cy, r, 0xE0141822);
		ring(g, cx, cy, r - 1.1f, r, 0xFF3A4458);
		for (int i = 0; i < 24; i++) {
			double a = -yawRad + i * Math.PI / 12;
			boolean major = i % 6 == 0;
			float r0 = r - (major ? 4.2f : 2.6f), r1 = r - 1.6f;
			float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
			line(g, cx + sx * r0, cy + sy * r0, cx + sx * r1, cy + sy * r1, major ? 0.9f : 0.5f, major ? 0xFFD8DEE8 : 0x90A9B3C4);
		}
		String[] keys = {"maqueta.compass.n", "maqueta.compass.e", "maqueta.compass.s", "maqueta.compass.w"};
		for (int i = 0; i < 4; i++) {
			double a = -yawRad + i * Math.PI / 2;
			float lr = r - 8.5f;
			float x = cx + (float) Math.sin(a) * lr, y = cy - (float) Math.cos(a) * lr;
			String t = Component.translatable(keys[i]).getString();
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(0.75f, 0.75f);
			g.text(font, t, -font.width(t) / 2, -4, i == 0 ? NORTH : TEXT, false);
			g.pose().popMatrix();
		}
		// Needle: red half to the north, light half to the south.
		float na = -yawRad;
		float sx = (float) Math.sin(na), sy = (float) -Math.cos(na);
		float px = -sy, py = sx;
		float len = r - 12.5f, half = 1.9f;
		triangle(g, cx, cy, sx * len, sy * len, px * half, py * half, -px * half, -py * half, NORTH);
		triangle(g, cx, cy, -sx * len, -sy * len, px * half, py * half, -px * half, -py * half, 0xFFE8ECF2);
		disc(g, cx, cy, 1.4f, 0xFF141822);
		disc(g, cx, cy, 0.8f, 0xFFE8ECF2);
	}

	/** Cardinal name for a heading in degrees (0 = north, clockwise). */
	public static String heading(double deg) {
		String[] k = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
		int i = (int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8;
		return Component.translatable("maqueta.compass." + k[i]).getString();
	}

	/**
	 * A creature icon: a round badge in its category colour with the creature's own picture
	 * (player face or spawn egg), plus a small height marker when it is above or below you.
	 */
	/** A far creature: just a coloured dot (one draw). */
	public static void dot(GuiGraphicsExtractor g, SceneMarkers.Icon icon, float x, float y, float r) {
		Badges.disc(g, x, y, r + 0.7f, ((int) (0xB0 * icon.alpha()) << 24) | 0x141822);
		Badges.disc(g, x, y, r, ((int) (255 * icon.alpha()) << 24) | icon.color());
	}

	public static void icon(GuiGraphicsExtractor g, Font font, SceneMarkers.Icon icon, float x, float y, float size) {
		float r = size * 0.5f;
		float a = icon.alpha();
		int ring = ((int) (a * 255) << 24) | icon.color();
		Badges.disc(g, x, y + 0.5f, r + 1.1f, ((int) (0x60 * a) << 24));
		Badges.disc(g, x, y, r + 0.8f, ring);
		Badges.disc(g, x, y, r, ((int) (0xF0 * a) << 24) | 0x141822);
		if (icon.entity() instanceof AbstractClientPlayer p) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			float s = (size - 3f) / 8f;
			g.pose().scale(s, s);
			PlayerFaceExtractor.extractRenderState(g, p.getSkin(), -4, -4, 8, ((int) (a * 255) << 24) | 0xFFFFFF);
			g.pose().popMatrix();
		} else if (!MobFaces.draw(g, icon.entity(), x, y, size - 3f, a)) {
			String t = icon.entity().getType().getDescription().getString();
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(0.75f, 0.75f);
			g.text(font, t.isEmpty() ? "?" : t.substring(0, 1), -3, -4, TEXT, false);
			g.pose().popMatrix();
		}
		if (Math.abs(icon.dy()) >= 3) {
			String h = (icon.dy() > 0 ? "▲" : "▼") + Math.abs(icon.dy());
			g.pose().pushMatrix();
			g.pose().translate(x + r * 0.55f, y + r * 0.55f);
			g.pose().scale(0.5f, 0.5f);
			int w = font.width(h);
			g.fill(-1, -1, w + 1, 8, 0xD0141822);
			g.text(font, h, 0, 0, icon.dy() > 0 ? 0xFFB9E3FF : 0xFFFFD39A, false);
			g.pose().popMatrix();
		}
	}
}
