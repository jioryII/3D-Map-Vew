package dev.maqueta.client.hud;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Round badges baked once into small textures and then drawn with a single tinted blit.
 * Drawing circles out of pixel strips cost about a hundred GUI elements per icon, which is what
 * made many nearby creatures lag; now an icon is two or three draws.
 */
public final class Badges {
	public static final Identifier DISC = Identifier.fromNamespaceAndPath("maqueta", "badge/disc");
	public static final Identifier RING = Identifier.fromNamespaceAndPath("maqueta", "badge/ring");
	private static final int SIZE = 64;
	private static boolean ready;

	private Badges() {
	}

	private static void ensure() {
		if (ready) return;
		ready = true;
		Minecraft.getInstance().getTextureManager().register(DISC, new DynamicTexture(() -> "Maqueta badge disc", circle(0f, 1f)));
		Minecraft.getInstance().getTextureManager().register(RING, new DynamicTexture(() -> "Maqueta badge ring", circle(0.78f, 1f)));
	}

	/** White anti-aliased circle (or ring between r0 and r1, as fractions of the radius). */
	private static NativeImage circle(float r0, float r1) {
		NativeImage img = new NativeImage(SIZE, SIZE, true);
		float c = SIZE / 2f, R = SIZE / 2f - 0.5f;
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				float d = (float) Math.hypot(x + 0.5f - c, y + 0.5f - c) / R;
				float outer = Math.max(0f, Math.min(1f, (r1 - d) * R));
				float inner = r0 <= 0f ? 1f : Math.max(0f, Math.min(1f, (d - r0) * R));
				int a = Math.round(outer * inner * 255);
				img.setPixel(x, y, (a << 24) | 0xFFFFFF);
			}
		}
		return img;
	}

	/** A filled disc of radius r (GUI units) centred at (x, y), tinted ARGB. */
	public static void disc(GuiGraphicsExtractor g, float x, float y, float r, int argb) {
		draw(g, DISC, x, y, r, argb);
	}

	public static void ring(GuiGraphicsExtractor g, float x, float y, float r, int argb) {
		draw(g, RING, x, y, r, argb);
	}

	private static void draw(GuiGraphicsExtractor g, Identifier tex, float x, float y, float r, int argb) {
		ensure();
		g.pose().pushMatrix();
		g.pose().translate(x - r, y - r);
		g.pose().scale(r * 2f / SIZE, r * 2f / SIZE);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, SIZE, SIZE, SIZE, SIZE, SIZE, SIZE, argb);
		g.pose().popMatrix();
	}
}
