package dev.maqueta.client.hud;

import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.core.light.TimeGrading;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Small arc in the top-left corner: the sun travels it by day, the moon (with its real phase)
 * by night, with the world clock beside it. The maqueta's shadows never move; only its
 * colour grading follows this clock.
 */
public final class SunMoonIndicator {
	private SunMoonIndicator() {
	}

	public static void extract(GuiGraphicsExtractor g, Minecraft mc, MapSession session) {
		if (session.dimensionKind != TimeGrading.Dimension.OVERWORLD) return;
		long time = mc.level.getOverworldClockTime();
		long dayTime = Math.floorMod(time, 24000L);
		boolean day = dayTime < 12000;
		float progress = (day ? dayTime : dayTime - 12000) / 12000f;

		int cx = 26, cy = 24, r = 17;
		// Arc of soft dots.
		for (int i = 0; i <= 16; i++) {
			double a = Math.PI * (1 - i / 16.0);
			int x = (int) Math.round(cx + Math.cos(a) * r);
			int y = (int) Math.round(cy - Math.sin(a) * r);
			int alpha = i / 16f <= progress ? 0x90 : 0x40;
			g.fill(x, y, x + 1, y + 1, (alpha << 24) | 0xE8ECF2);
		}
		g.fill(cx - r - 3, cy + 1, cx + r + 4, cy + 2, 0x50E8ECF2);

		double a = Math.PI * (1 - progress);
		int bx = (int) Math.round(cx + Math.cos(a) * r);
		int by = (int) Math.round(cy - Math.sin(a) * r);
		if (day) {
			disc(g, bx, by, 5, 0x40FFD36B);
			disc(g, bx, by, 3, 0xFFFFD36B);
		} else {
			int phase = (int) Math.floorMod(time / 24000L, 8L);
			moon(g, bx, by, phase);
		}

		Font font = mc.font;
		MaquetaConfig cfg = MaquetaConfig.get();
		String clock = TimeGrading.clock(dayTime, cfg.twelveHourClock);
		StringBuilder sb = new StringBuilder(clock);
		if (mc.level.isThundering()) sb.append("  ⚡");
		else if (mc.level.isRaining()) sb.append("  ☂");
		g.text(font, sb.toString(), cx + r + 8, cy - 9, 0xFFE8ECF2, true);
		if (cfg.showDayCounter) {
			String dayText = Component.translatable("maqueta.hud.day", time / 24000L + 1).getString();
			g.pose().pushMatrix();
			g.pose().translate(cx + r + 8, cy + 1);
			g.pose().scale(0.75f, 0.75f);
			g.text(font, dayText, 0, 0, 0xC0E8ECF2, true);
			g.pose().popMatrix();
		}
	}

	private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
		for (int dy = -r; dy <= r; dy++) {
			int w = (int) Math.round(Math.sqrt(r * r - dy * dy + 0.25));
			g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, argb);
		}
	}

	/** Phase 0 = full moon (Minecraft order), 4 = new moon. */
	private static void moon(GuiGraphicsExtractor g, int cx, int cy, int phase) {
		int r = 3;
		disc(g, cx, cy, r + 2, 0x30C8D4F0);
		float lit = switch (phase) {
			case 0 -> 1f;
			case 1, 7 -> 0.75f;
			case 2, 6 -> 0.5f;
			case 3, 5 -> 0.25f;
			default -> 0f;
		};
		boolean waning = phase >= 1 && phase <= 3;
		for (int dy = -r; dy <= r; dy++) {
			int w = (int) Math.round(Math.sqrt(r * r - dy * dy + 0.25));
			int total = w * 2 + 1;
			int litW = Math.round(total * lit);
			int x0 = cx - w;
			g.fill(x0, cy + dy, x0 + total, cy + dy + 1, 0xFF3A4258);
			if (litW > 0) {
				if (waning) g.fill(x0, cy + dy, x0 + litW, cy + dy + 1, 0xFFE4EAF7);
				else g.fill(x0 + total - litW, cy + dy, x0 + total, cy + dy + 1, 0xFFE4EAF7);
			}
		}
	}
}
