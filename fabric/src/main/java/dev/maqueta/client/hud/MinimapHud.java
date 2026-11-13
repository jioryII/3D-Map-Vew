package dev.maqueta.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.render.MapRenderer;
import dev.maqueta.client.render.SceneMarkers;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.core.camera.OrbitCamera;
import dev.maqueta.core.light.TimeGrading;
import dev.maqueta.core.marker.Waypoint;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;

import java.util.List;
import java.util.Locale;

/**
 * The borderless 3D minimap: a piece of the maqueta floating in a corner that dissolves into
 * the HUD instead of sitting in a frame. Rendered on the GPU into its own texture at 2x and
 * composited with premultiplied alpha.
 */
public final class MinimapHud {
	private static final MapRenderer RENDERER = new MapRenderer("minimap");
	private static final OrbitCamera CAMERA = new OrbitCamera();
	private static long lastNanos;
	private static boolean initialised;
	private static float caveZoom = 1f;

	private MinimapHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		MaquetaConfig cfg = MaquetaConfig.get();
		MapSession session = MapSession.get();
		if (session == null || mc.player == null || mc.level == null) return;
		if (cfg.showSunMoon) SunMoonIndicator.extract(graphics, mc, session);
		if (!cfg.minimapEnabled || !session.minimapAllowed()) return;
		if (mc.gui.screen() instanceof AbstractContainerScreen<?>) return; // never cover inventories or other mods' screens
		if (mc.gui.screen() instanceof dev.maqueta.client.screen.MapScreen) return;

		float partial = delta.getGameTimeDeltaPartialTick(false);
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : (now - lastNanos) / 1e9f;
		lastNanos = now;

		int size = cfg.minimapSize;
		int[] pos = cfg.anchor.place(graphics.guiWidth(), graphics.guiHeight(), size, size, cfg.offsetX, cfg.offsetY);
		int guiScale = mc.getWindow().getGuiScale();
		int px = Math.max(16, size * guiScale * 2); // 2x supersampling, box-filtered by the linear blit

		Vec3 p = mc.player.getPosition(partial);
		boolean cave = session.cave.active() && session.caveLayerAllowed();
		caveZoom += ((cave ? 0.62f : 1f) - caveZoom) * Math.min(1f, dt * 4f);
		float distance = cfg.minimapDistance * caveZoom;
		float yaw = cfg.rotateWithPlayer ? (float) Math.toRadians(mc.player.getViewYRot(partial)) + (float) Math.PI : 0f;
		if (!initialised) {
			CAMERA.snapPose(yaw, (float) Math.toRadians(cfg.minimapPitchDeg), distance);
			initialised = true;
		}
		CAMERA.setAutoTilt(false);
		CAMERA.snapTo(p.x, p.y, p.z);
		CAMERA.setYaw(yaw);
		CAMERA.setDistance(distance);
		CAMERA.rotate(0, (float) Math.toRadians(cfg.minimapPitchDeg) - CAMERA.goalPitch());
		CAMERA.setSmoothing(cfg.reduceMotion ? 40f : 16f);
		CAMERA.update(dt);
		CAMERA.snapTo(p.x, p.y, p.z);

		TimeGrading.Grade grade = grade(mc, session);
		RENDERER.prepareCamera(CAMERA, session, px, px);
		List<SceneMarkers.Label> labels = SceneMarkers.build(RENDERER, session, mc, partial, CAMERA.distance(), true);
		int radius = (int) Math.ceil(CAMERA.distance() * 2.4 / 16.0) + 2;
		MapRenderer.Params params = new MapRenderer.Params(MapRenderer.Mode.MINIMAP, grade, seconds(), 1e6f, 0.66f, 0.99f,
			cfg.veilOpacity, CAMERA.distance() * 1.2f, Math.min(radius, 24), 1.4f);
		GpuTextureView view = RENDERER.render(session, CAMERA, px, px, params);

		blit(graphics, view, pos[0], pos[1], pos[0] + size, pos[1] + size);

		Font font = mc.font;
		float scale = size / (float) px;
		if (cfg.showCompass) drawCompass(graphics, font, session, pos, size, scale);
		for (SceneMarkers.Label l : labels) {
			float[] sp = RENDERER.project(session, l.x(), l.y(), l.z());
			if (sp == null) continue;
			float lx = pos[0] + sp[0] * scale, ly = pos[1] + sp[1] * scale;
			float dx = lx - (pos[0] + size / 2f), dy = ly - (pos[1] + size / 2f);
			if (dx * dx + dy * dy > (size * 0.42f) * (size * 0.42f)) continue;
			drawLabel(graphics, font, l.text(), lx, ly, l.color(), 0.75f);
		}
		drawFooter(graphics, font, session, mc, pos, size);
	}

	static TimeGrading.Grade grade(Minecraft mc, MapSession session) {
		long dayTime = mc.level.getOverworldClockTime();
		float rain = mc.level.getRainLevel(1f);
		float thunder = mc.level.getThunderLevel(1f);
		return TimeGrading.grade(session.dimensionKind, dayTime, rain, thunder);
	}

	static float seconds() {
		return (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000f;
	}

	public static void blit(GuiGraphicsExtractor graphics, GpuTextureView view, int x0, int y0, int x1, int y1) {
		graphics.guiRenderState.addGuiElement(new BlitRenderState(
			RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
			TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
			new Matrix3x2f(graphics.pose()),
			x0, y0, x1, y1,
			0f, 1f, 1f, 0f,
			-1,
			graphics.scissorStack.peek()));
	}

	private static void drawCompass(GuiGraphicsExtractor g, Font font, MapSession s, int[] pos, int size, float scale) {
		double tx = CAMERA.targetX(), ty = CAMERA.targetY(), tz = CAMERA.targetZ();
		double reach = CAMERA.distance() * 0.9;
		String[] letters = {
			Component.translatable("maqueta.compass.n").getString(),
			Component.translatable("maqueta.compass.e").getString(),
			Component.translatable("maqueta.compass.s").getString(),
			Component.translatable("maqueta.compass.w").getString()};
		double[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
		float cx = pos[0] + size / 2f, cy = pos[1] + size / 2f;
		float ring = size * 0.40f;
		for (int i = 0; i < 4; i++) {
			float[] sp = RENDERER.project(s, tx + dirs[i][0] * reach, ty, tz + dirs[i][1] * reach);
			if (sp == null) continue;
			float lx = pos[0] + sp[0] * scale - cx, ly = pos[1] + sp[1] * scale - cy;
			float len = (float) Math.sqrt(lx * lx + ly * ly);
			if (len < 1e-3f) continue;
			// Letters rest on the ring where the fog begins, following the ground's perspective.
			float squash = Math.min(1f, len / ring);
			lx = lx / len * ring;
			ly = ly / len * ring * (0.75f + 0.25f * squash);
			int color = i == 0 ? 0xFFF05A4F : 0xFFE8ECF2;
			drawLabel(g, font, letters[i], cx + lx, cy + ly, color, i == 0 ? 1f : 0.85f);
		}
	}

	static void drawLabel(GuiGraphicsExtractor g, Font font, String text, float x, float y, int color, float scale) {
		int w = font.width(text);
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, text, -w / 2, -4, 0xFF000000 | color, true);
		g.pose().popMatrix();
	}

	private static void drawFooter(GuiGraphicsExtractor g, Font font, MapSession s, Minecraft mc, int[] pos, int size) {
		MaquetaConfig cfg = MaquetaConfig.get();
		boolean below = pos[1] + size + 22 < g.guiHeight();
		int y = below ? pos[1] + size - 6 : pos[1] - 4;
		int cx = pos[0] + size / 2;
		if (cfg.showCoordinates) {
			String coords = String.format(Locale.ROOT, "%d, %d, %d", mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
			drawLabel(g, font, coords, cx, y, 0xE8ECF2, 0.75f);
			y += below ? 8 : -8;
		}
		Waypoint t = s.waypoints.target();
		if (t != null && t.dimension.equals(s.dimension)) {
			double d = t.distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ());
			drawLabel(g, font, "◆ " + t.name + "  " + Math.round(d) + " m", cx, y, t.color, 0.75f);
		}
	}
}
