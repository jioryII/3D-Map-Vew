package dev.maqueta.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws a creature's real face, cut straight from its own skin: the front of the head box
 * in the entity texture. Works for the vanilla mobs below and, through the common
 * {@code (8, 8)} head layout, for most modded humanoids and blocky mobs too.
 */
public final class MobFaces {
	/** Front face of the head, in texels of a texture whose width is {@code texW}. */
	private record Face(int u, int v, int w, int h, int texW) {
	}

	private static final Face DEFAULT = new Face(8, 8, 8, 8, 64);
	private static final Map<String, Face> FACES = new HashMap<>();

	static {
		Face humanoid = new Face(8, 8, 8, 8, 64);
		for (String id : new String[]{"zombie", "husk", "drowned", "skeleton", "stray", "wither_skeleton", "bogged", "creeper",
			"enderman", "piglin", "piglin_brute", "zombified_piglin", "pillager", "vindicator", "evoker", "illusioner", "vex",
			"slime", "magma_cube", "pig", "zombie_villager", "allay", "blaze", "player"}) FACES.put(id, humanoid);
		Face villager = new Face(8, 8, 8, 10, 64);
		FACES.put("villager", villager);
		FACES.put("wandering_trader", villager);
		FACES.put("witch", villager);
		FACES.put("iron_golem", new Face(8, 8, 8, 10, 128));
		FACES.put("cow", new Face(6, 6, 8, 8, 64));
		FACES.put("mooshroom", new Face(6, 6, 8, 8, 64));
		FACES.put("sheep", new Face(8, 8, 6, 6, 64));
		FACES.put("chicken", new Face(3, 3, 4, 6, 64));
		FACES.put("wolf", new Face(4, 4, 6, 6, 64));
		FACES.put("cat", new Face(5, 5, 5, 4, 64));
		FACES.put("ocelot", new Face(5, 5, 5, 4, 64));
		FACES.put("spider", new Face(40, 12, 8, 8, 64));
		FACES.put("cave_spider", new Face(40, 12, 8, 8, 64));
		FACES.put("snow_golem", new Face(8, 8, 8, 8, 64));
		FACES.put("goat", new Face(5, 49, 5, 10, 64));
		FACES.put("polar_bear", new Face(7, 7, 7, 7, 128));
		FACES.put("panda", new Face(9, 15, 13, 10, 64));
		FACES.put("turtle", new Face(3, 3, 6, 5, 128));
		FACES.put("frog", new Face(7, 7, 7, 3, 48));
	}

	private record Cached(Identifier texture, long until) {
	}

	private static final Map<UUID, Cached> TEXTURES = new HashMap<>();

	private MobFaces() {
	}

	/** Draws the face centred on (x, y) with the given size (GUI units). Returns false if not possible. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean draw(GuiGraphicsExtractor g, Entity e, float x, float y, float size, float alpha) {
		Identifier tex = texture(e);
		if (tex == null) return false;
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		Face f = FACES.getOrDefault(id, DEFAULT);
		AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(tex);
		int tw = t.getTexture().getWidth(0), th = t.getTexture().getHeight(0);
		float scale = tw / (float) f.texW;
		float u = f.u * scale, v = f.v * scale, w = f.w * scale, h = f.h * scale;
		float aspect = f.h / (float) f.w;
		int half = Math.max(1, Math.round(size * 0.5f));
		int hh = Math.max(1, Math.round(half * aspect));
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
		int color = (a << 24) | 0xFFFFFF;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, -half, -hh, u, v, half * 2, hh * 2, Math.round(w), Math.round(h), tw, th, color);
		g.pose().popMatrix();
		return true;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Identifier texture(Entity e) {
		long now = System.currentTimeMillis();
		Cached c = TEXTURES.get(e.getUUID());
		if (c != null && c.until > now) return c.texture;
		Identifier id = null;
		try {
			EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
			if (renderer instanceof LivingEntityRenderer living) {
				EntityRenderState state = renderer.createRenderState(e, 1f);
				id = living.getTextureLocation((net.minecraft.client.renderer.entity.state.LivingEntityRenderState) state);
			}
		} catch (Throwable ignored) {
		}
		if (TEXTURES.size() > 512) TEXTURES.clear();
		TEXTURES.put(e.getUUID(), new Cached(id, now + 5000));
		return id;
	}
}
