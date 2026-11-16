package dev.maqueta.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.maqueta.client.MaquetaClient;
import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.hud.MapUi;
import dev.maqueta.client.render.MapRenderer;
import dev.maqueta.client.render.SceneMarkers;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.client.world.RouteTracker;
import dev.maqueta.client.world.WaypointShare;
import dev.maqueta.client.world.Workers;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.camera.HeightfieldRaycast;
import dev.maqueta.core.camera.OrbitCamera;
import dev.maqueta.core.light.TimeGrading;
import dev.maqueta.core.marker.Waypoint;
import dev.maqueta.core.route.RoutePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full map: the explored world as a maqueta on a cartographer's table, under a free
 * Google-Earth style camera. Does not pause the game. Every input moves a goal and the
 * camera eases towards it.
 */
public final class MapScreen extends Screen {
	private static final MapRenderer RENDERER = new MapRenderer("map");
	/** Last pose per world+dimension (design 4.3). */
	private static final Map<String, float[]> POSES = new HashMap<>();
	private static final Map<String, double[]> TARGETS = new HashMap<>();
	private static String openedThisSession;

	private final OrbitCamera camera = new OrbitCamera();
	private long lastNanos;
	private long openedAt;
	private float fade;
	private boolean dragging;
	private int dragButton;
	private double dragTravel;
	private double lastDx, lastDy;
	private long lastDragNanos;
	private long pressNanos;
	private double[] hover;
	private boolean followPlayer;
	private final List<Hit> waypointHits = new ArrayList<>();
	private float helpAlpha = 1f;
	private boolean helpDismissed;
	private ContextMenu menu;
	private String toast;
	private long toastUntil;

	private record Hit(Waypoint waypoint, float x, float y) {
	}

	private record MenuItem(String key, Runnable action) {
	}

	private static final class ContextMenu {
		final double[] point;
		final int x, y;
		final List<MenuItem> items = new ArrayList<>();

		ContextMenu(double[] point, int x, int y) {
			this.point = point;
			this.x = x;
			this.y = y;
		}
	}

	public MapScreen() {
		super(Component.translatable("maqueta.map.title"));
	}

	/** The orbit camera, for scripted views (game tests, other mods). */
	public OrbitCamera camera() {
		return camera;
	}

	public void hideHelp() {
		helpAlpha = 0f;
		helpDismissed = true;
	}

	@Override
	protected void init() {
		MapSession s = MapSession.get();
		Minecraft mc = Minecraft.getInstance();
		if (s == null || mc.player == null) return;
		String key = s.worldId + "|" + s.dimension;
		float[] pose = POSES.get(key);
		double[] target = TARGETS.get(key);
		boolean first = !key.equals(openedThisSession);
		openedThisSession = key;
		double tx = mc.player.getX(); // always open centred on the player
		double ty = mc.player.getY();
		double tz = mc.player.getZ();
		float yaw = pose != null ? pose[0] : (float) Math.toRadians(mc.player.getYRot()) + (float) Math.PI;
		float pitch = pose != null ? pose[1] : (float) Math.toRadians(55);
		float dist = pose != null ? pose[2] : 140f;
		camera.setAutoTilt(MaquetaConfig.get().autoTilt);
		boolean reduce = MaquetaConfig.get().reduceMotion;
		camera.snapTo(tx, ty, tz);
		if (first && !reduce) {
			// Opening: start top-down and high, descend onto the saved pose (~900 ms).
			camera.snapPose(yaw + 0.35f, OrbitCamera.MAX_PITCH, dist * 5f);
			camera.setSmoothing(4.5f);
		} else {
			camera.snapPose(yaw, pitch, dist * (reduce ? 1f : 1.12f));
			camera.setSmoothing(12f);
		}
		camera.setYaw(yaw);
		camera.rotate(0, pitch - camera.goalPitch());
		camera.setDistance(dist);
		openedAt = System.nanoTime();
		lastNanos = 0;
		fade = 0f;
		if (s.hasRoofLayer()) {
			// Nether: open on the layer you are in (above the bedrock roof or inside).
			s.setRoofView(s.playerAboveRoof());
			s.layerPinned = true;
		}
	}

	private void toggleRoofView(MapSession s) {
		s.setRoofView(!s.roofView());
		s.layerPinned = true;
		Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable(s.roofView() ? "maqueta.nether.roof" : "maqueta.nether.inside"), false);
	}

	@Override
	public boolean isPauseScreen() {
		return MaquetaConfig.get().pauseWhenMapOpen; // singleplayer: the world waits while you read the map
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// The maqueta is the background.
	}

	@Override
	public void removed() {
		MapSession s = MapSession.get();
		if (s != null) {
			String key = s.worldId + "|" + s.dimension;
			POSES.put(key, new float[]{camera.goalYaw(), camera.goalPitch(), camera.goalDistance()});
			TARGETS.put(key, new double[]{camera.goalX(), camera.goalY(), camera.goalZ()});
			s.layerPinned = false; // back to following the player (minimap)
		}
		super.removed();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		Minecraft mc = Minecraft.getInstance();
		MapSession s = MapSession.get();
		if (s == null || mc.player == null || mc.level == null) {
			onClose();
			return;
		}
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : (now - lastNanos) / 1e9f;
		lastNanos = now;
		float sinceOpen = (now - openedAt) / 1e9f;
		if (sinceOpen > 0.9f) camera.setSmoothing(MaquetaConfig.get().reduceMotion ? 30f : 12f);
		fade = Math.min(1f, fade + dt / 0.25f);
		helpAlpha = helpDismissed ? 0f : (sinceOpen < 6f ? 1f : Math.max(0f, helpAlpha - dt * 0.8f));

		if (followPlayer) camera.setTarget(mc.player.getX(), mc.player.getY(), mc.player.getZ());
		int ground = s.store.top((int) Math.floor(camera.goalX()), (int) Math.floor(camera.goalZ()));
		if (ground != ChunkColumns.UNKNOWN && !s.cave.active()) camera.moveTargetY(ground);
		camera.update(dt);

		int guiScale = mc.getWindow().getGuiScale();
		float ss = MaquetaConfig.get().supersampling;
		int pw = Math.max(1, Math.round(width * guiScale * ss));
		int ph = Math.max(1, Math.round(height * guiScale * ss));

		RENDERER.prepareCamera(camera, s, pw, ph);
		SceneMarkers.Result scene = SceneMarkers.build(RENDERER, s, mc, partial, camera.distance(), false);
		TimeGrading.Grade grade = TimeGrading.grade(s.dimensionKind, mc.level.getOverworldClockTime(), mc.level.getRainLevel(1f), mc.level.getThunderLevel(1f));
		// The whole explored world stays visible at any zoom; far chunks use the coarse levels.
		int radius = (int) Math.min(256, Math.max(MaquetaConfig.get().renderRadiusChunks, Math.ceil(camera.distance() * 4.0 / 16.0) + 8));
		float fogRadius = Math.max(96f, radius * 16f * 0.92f);
		float lodBias = Math.max(0.8f, ph / 1080f);
		MapRenderer.Params params = new MapRenderer.Params(MapRenderer.Mode.FULL_MAP, grade, seconds(), fogRadius, 0, 1, 0,
			camera.distance() * 3f, radius, lodBias, 900f, true,
			new org.joml.Vector3f((float) (mc.player.getX() - s.anchorX), (float) mc.player.getY(), (float) (mc.player.getZ() - s.anchorZ)),
			0f /* see-through only on the minimap */, 0.00020f * MaquetaConfig.get().haze,
			MaquetaConfig.get().clouds && s.dimensionKind != TimeGrading.Dimension.END ? 0.95f * smooth(140f, 520f, camera.distance()) : 0f,
			210f, MaquetaConfig.get().edgeRelief);
		GpuTextureView view = RENDERER.render(s, camera, pw, ph, params);
		int a = Math.round(fade * 255);
		g.guiRenderState.addGuiElement(new BlitRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
			TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
			new Matrix3x2f(g.pose()), 0, 0, width, height, 0f, 1f, 1f, 0f, (a << 24) | (a << 16) | (a << 8) | a, g.scissorStack.peek()));

		Font font = mc.font;
		float toGui = width / (float) pw;

		// Creature icons at their real 3D height.
		float iconSize = Math.max(9f, Math.min(16f, 900f / Math.max(40f, camera.distance()) + 6f));
		int faceFrom = 0, iconIndex = 0; // every creature shows its face (badges are cheap now)
		for (SceneMarkers.Icon icon : scene.icons()) {
			boolean face = iconIndex++ >= faceFrom;
			float[] sp = RENDERER.project(s, icon.x(), icon.y(), icon.z());
			if (sp == null || sp[2] < 0f || sp[2] > 1f) continue;
			float ix = sp[0] * toGui, iy = sp[1] * toGui;
			if (ix < -20 || iy < -20 || ix > width + 20 || iy > height + 20) continue;
			if (face) MapUi.icon(g, font, icon, ix, iy - iconSize * 0.5f, iconSize);
			else MapUi.dot(g, icon, ix, iy, 2.2f);
		}

		// Labels.
		waypointHits.clear();
		for (SceneMarkers.Label l : scene.labels()) {
			float[] sp = RENDERER.project(s, l.x(), l.y(), l.z());
			if (sp == null || sp[2] < 0f || sp[2] > 1f) continue;
			float lx = sp[0] * toGui, ly = sp[1] * toGui;
			if (lx < -50 || ly < -20 || lx > width + 50 || ly > height + 20) continue;
			drawPill(g, font, l.text(), lx, ly, l.color());
			for (Waypoint w : s.waypoints.inDimension(s.dimension)) {
				if (w.name.equals(l.text()) && Math.abs(w.x + 0.5 - l.x()) < 0.01 && Math.abs(w.z + 0.5 - l.z()) < 0.01) {
					waypointHits.add(new Hit(w, lx, ly));
				}
			}
		}

		hover = menu == null ? pick(s, mouseX, mouseY) : hover;
		drawChrome(g, font, s, mc, mouseX, mouseY);
		drawSettings(g, font, mouseX, mouseY);
		drawMenu(g, font, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partial);
	}

	private static float smooth(float a, float b, float v) {
		float t = Math.max(0f, Math.min(1f, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	// --- quick settings panel (gear button) ---

	private boolean settingsOpen;

	private record Setting(String key, java.util.function.Supplier<String> value, Runnable next) {
	}

	private static String onOff(boolean b) {
		return Component.translatable(b ? "options.on" : "options.off").getString();
	}

	private List<Setting> settings() {
		MaquetaConfig c = MaquetaConfig.get();
		List<Setting> out = new ArrayList<>();
		out.add(new Setting("maqueta.config.minimap", () -> onOff(c.minimapEnabled), () -> c.minimapEnabled = !c.minimapEnabled));
		out.add(new Setting("maqueta.panel.size", () -> String.valueOf(c.minimapSize), () -> c.minimapSize = c.minimapSize >= 220 ? 88 : c.minimapSize + 22));
		out.add(new Setting("maqueta.panel.reach", () -> Math.round(c.minimapZoomLevels[c.minimapZoomIndex]) + " m",
			() -> c.minimapZoomIndex = (c.minimapZoomIndex + 1) % c.minimapZoomLevels.length));
		out.add(new Setting("maqueta.panel.position", () -> Component.translatable(c.anchor.key()).getString(), () -> c.anchor = c.anchor.next()));
		out.add(new Setting("maqueta.config.rotate", () -> onOff(c.rotateWithPlayer), () -> c.rotateWithPlayer = !c.rotateWithPlayer));
		out.add(new Setting("maqueta.panel.trail", () -> c.showTrail ? c.trailLength + " m" : onOff(false), () -> {
			int[] steps = {250, 500, 1000, 2000};
			if (!c.showTrail) {
				c.showTrail = true;
				c.trailLength = steps[0];
				return;
			}
			int k = 0;
			while (k < steps.length && steps[k] <= c.trailLength) k++;
			if (k >= steps.length) c.showTrail = false;
			else c.trailLength = steps[k];
		}));
		out.add(new Setting("maqueta.panel.clouds", () -> onOff(c.clouds), () -> c.clouds = !c.clouds));
		out.add(new Setting("maqueta.panel.haze", () -> String.format(Locale.ROOT, "%.1f", c.haze), () -> c.haze = c.haze >= 2f ? 0f : c.haze + 0.5f));
		out.add(new Setting("maqueta.panel.relief", () -> onOff(c.edgeRelief), () -> c.edgeRelief = !c.edgeRelief));
		out.add(new Setting("maqueta.panel.import", () -> onOff(c.importOtherMaps), () -> c.importOtherMaps = !c.importOtherMaps));
		out.add(new Setting("maqueta.panel.seethrough", () -> onOff(c.seeThrough), () -> c.seeThrough = !c.seeThrough));
		out.add(new Setting("maqueta.config.entities", () -> onOff(c.showEntities), () -> c.showEntities = !c.showEntities));
		out.add(new Setting("maqueta.config.hostile", () -> onOff(c.showHostile), () -> c.showHostile = !c.showHostile));
		out.add(new Setting("maqueta.panel.quality", () -> String.format(Locale.ROOT, "%.2fx", c.supersampling),
			() -> c.supersampling = c.supersampling >= 2f ? 1f : c.supersampling + 0.25f));
		return out;
	}

	private int settingsX() {
		return width - 210;
	}

	private void drawSettings(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		if (!settingsOpen) return;
		List<Setting> list = settings();
		int x0 = settingsX(), y0 = 74, w = 156, h = list.size() * 14 + 22;
		MapUi.panel(g, x0, y0, x0 + w, y0 + h, 0xF0141822);
		g.text(font, Component.translatable("maqueta.panel.title"), x0 + 8, y0 + 6, MapUi.TEXT, false);
		for (int i = 0; i < list.size(); i++) {
			int iy = y0 + 18 + i * 14;
			boolean hot = mouseX >= x0 && mouseX < x0 + w && mouseY >= iy && mouseY < iy + 14;
			if (hot) g.fill(x0 + 2, iy, x0 + w - 2, iy + 14, 0x402F8CFF);
			Setting s = list.get(i);
			g.text(font, Component.translatable(s.key()), x0 + 8, iy + 3, hot ? MapUi.TEXT : MapUi.TEXT_DIM, false);
			String v = s.value().get();
			g.text(font, v, x0 + w - 8 - font.width(v), iy + 3, 0xFF8FC3FF, false);
		}
	}

	private boolean clickSettings(double mx, double my) {
		if (!settingsOpen) return false;
		List<Setting> list = settings();
		int x0 = settingsX(), y0 = 74, w = 156, h = list.size() * 14 + 22;
		if (mx < x0 || mx >= x0 + w || my < y0 || my >= y0 + h) return false;
		int i = (int) ((my - y0 - 18) / 14);
		if (my >= y0 + 18 && i >= 0 && i < list.size()) {
			list.get(i).next().run();
			MaquetaConfig.get().save();
		}
		return true;
	}

	private static float seconds() {
		return (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000f;
	}

	private void drawPill(GuiGraphicsExtractor g, Font font, String text, float x, float y, int color) {
		int w = font.width(text);
		int x0 = Math.round(x - w / 2f - 9), y0 = Math.round(y - 7);
		MapUi.panel(g, x0, y0, x0 + w + 14, y0 + 13, MapUi.PANEL);
		MapUi.disc(g, x0 + 5.5f, y0 + 6.5f, 2.2f, 0xFF000000 | color);
		g.text(font, text, x0 + 10, y0 + 3, MapUi.TEXT, false);
		MapUi.triangle(g, x, y0 + 13, -2.5f, 0, 2.5f, 0, 0, 3f, MapUi.PANEL);
	}

	private void drawChrome(GuiGraphicsExtractor g, Font font, MapSession s, Minecraft mc, int mouseX, int mouseY) {
		// Title card.
		String layer = Component.translatable(s.hasRoofLayer() ? (s.roofView() ? "maqueta.layer.roof" : "maqueta.layer.inside") : s.cave.active() ? "maqueta.layer.cave" : "maqueta.layer.surface").getString();
		String dim = Component.translatable("maqueta.dim." + s.dimension.substring(s.dimension.indexOf(':') + 1)).getString();
		int tw = Math.max(font.width(layer + "  ·  " + dim), font.width(title.getString())) + 22;
		MapUi.panel(g, 8, 8, 8 + tw, 36, MapUi.PANEL);
		g.fill(8, 8, 10, 36, MapUi.ACCENT);
		g.text(font, title, 16, 12, MapUi.TEXT, false);
		g.text(font, layer + "  ·  " + dim, 16, 24, MapUi.TEXT_DIM, false);

		// Compass with heading readout (click: north up).
		float ccx = width - 30, ccy = 32, cr = 22;
		MapUi.compass(g, font, ccx, ccy, cr, camera.yaw());
		double headingDeg = Math.toDegrees(camera.yaw());
		String head = MapUi.heading(headingDeg) + " " + Math.round(((headingDeg % 360) + 360) % 360) + "°";
		g.pose().pushMatrix();
		g.pose().translate(ccx, ccy + cr + 5);
		g.pose().scale(0.75f, 0.75f);
		g.text(font, head, -font.width(head) / 2, 0, MapUi.TEXT_DIM, true);
		g.pose().popMatrix();

		// Side buttons: layer, zoom, me.
		sideButton(g, font, width - 44, 74, s.hasRoofLayer() ? (s.roofView() ? "▲" : "▼") : (s.cave.active() ? "⛏" : "☀"), mouseX, mouseY);
		sideButton(g, font, width - 44, 96, "+", mouseX, mouseY);
		sideButton(g, font, width - 44, 116, "−", mouseX, mouseY);
		sideButton(g, font, width - 44, 138, "◎", mouseX, mouseY);
		sideButton(g, font, width - 44, 160, "⚙", mouseX, mouseY);

		// Scale bar.
		float blocksPerPixel = camera.distance() * (float) Math.tan(Math.toRadians(OrbitCamera.FOV_DEG / 2)) * 2f / height;
		int[] steps = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};
		int meters = steps[steps.length - 1];
		for (int st : steps) {
			if (st / blocksPerPixel > 40) {
				meters = st;
				break;
			}
		}
		int bar = Math.round(meters / blocksPerPixel);
		int bx = 14, by = height - 16;
		MapUi.panel(g, bx - 6, by - 10, bx + bar + font.width(RouteTracker.formatDistance(meters)) + 12, by + 6, MapUi.PANEL);
		g.fill(bx, by, bx + bar, by + 2, 0xFFF2F4F8);
		g.fill(bx, by - 3, bx + 1, by + 2, 0xFFF2F4F8);
		g.fill(bx + bar / 2, by - 2, bx + bar / 2 + 1, by + 2, 0xB0F2F4F8);
		g.fill(bx + bar - 1, by - 3, bx + bar, by + 2, 0xFFF2F4F8);
		g.text(font, RouteTracker.formatDistance(meters), bx + bar + 5, by - 4, MapUi.TEXT, false);

		drawRouteCard(g, font, s, mc);
		drawCursorCard(g, font, s, mc);

		String stats = String.format(Locale.ROOT, "%d chunks · %d draws · %.0f MB · %d jobs",
			s.store.size(), RENDERER.lastDrawCount(), s.meshes.gpuBytes() / 1048576.0, Workers.pending());
		g.pose().pushMatrix();
		g.pose().translate(width - 10, height - 7);
		g.pose().scale(0.5f, 0.5f);
		g.text(font, stats, -font.width(stats), 0, 0x90AEB7C6, false);
		g.pose().popMatrix();

		if (toast != null && System.currentTimeMillis() < toastUntil) {
			int w = font.width(toast);
			MapUi.panel(g, width / 2 - w / 2 - 8, 12, width / 2 + w / 2 + 8, 28, MapUi.PANEL_LIGHT);
			g.text(font, toast, width / 2 - w / 2, 16, MapUi.TEXT, false);
		}

		if (helpAlpha > 0.01f) drawHelp(g, font);
	}

	private void sideButton(GuiGraphicsExtractor g, Font font, int x, int y, String glyph, int mouseX, int mouseY) {
		boolean hot = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
		MapUi.panel(g, x, y, x + 18, y + 18, hot ? MapUi.PANEL_LIGHT : MapUi.PANEL);
		g.text(font, glyph, x + 9 - font.width(glyph) / 2, y + 5, hot ? MapUi.TEXT : MapUi.TEXT_DIM, false);
	}

	private void drawRouteCard(GuiGraphicsExtractor g, Font font, MapSession s, Minecraft mc) {
		if (!s.route.active()) return;
		RoutePlanner.Route r = s.route.route();
		double px = mc.player.getX(), pz = mc.player.getZ();
		List<String> lines = new ArrayList<>();
		lines.add("➜ " + s.route.label());
		if (r == null) {
			lines.add(Component.translatable("maqueta.route.planning").getString());
		} else if (r.isEmpty()) {
			lines.add(Component.translatable("maqueta.route.none").getString());
		} else {
			double rem = s.route.remaining(px, pz);
			String now = RouteTracker.formatTime(s.route.eta(px, pz, RouteTracker.speed(mc.player)));
			lines.add(RouteTracker.formatDistance(rem) + "  ·  " + now + "  " + Component.translatable("maqueta.route.mode." +
				RouteTracker.mode(mc.player).name().toLowerCase(Locale.ROOT)).getString());
			lines.add(Component.translatable("maqueta.route.walk").getString() + " " + RouteTracker.formatTime(s.route.eta(px, pz, RouteTracker.WALK))
				+ "   " + Component.translatable("maqueta.route.horse").getString() + " " + RouteTracker.formatTime(s.route.eta(px, pz, 9.7)));
			if (!r.complete()) lines.add(Component.translatable("maqueta.route.partial").getString());
		}
		int w = 0;
		for (String l : lines) w = Math.max(w, font.width(l));
		int x0 = 8, y0 = 42, h = lines.size() * 11 + 8;
		MapUi.panel(g, x0, y0, x0 + w + 16, y0 + h, MapUi.PANEL);
		g.fill(x0, y0, x0 + 2, y0 + h, 0xFF000000 | SceneMarkers.ROUTE);
		for (int i = 0; i < lines.size(); i++) {
			g.text(font, lines.get(i), x0 + 9, y0 + 5 + i * 11, i == 0 ? MapUi.TEXT : MapUi.TEXT_DIM, false);
		}
	}

	private void drawCursorCard(GuiGraphicsExtractor g, Font font, MapSession s, Minecraft mc) {
		if (hover == null) return;
		int x = (int) Math.floor(hover[0]), z = (int) Math.floor(hover[2]);
		ChunkColumns c = s.store.get(x >> 4, z >> 4);
		if (c == null) return;
		int i = ChunkColumns.index(x & 15, z & 15);
		if (!c.isKnown(i)) return;
		List<String> lines = new ArrayList<>();
		String biome = c.biomeName(i);
		if (!biome.isEmpty()) {
			Identifier id = Identifier.parse(biome);
			lines.add(Component.translatable("biome." + id.getNamespace() + "." + id.getPath()).getString());
		}
		String block = c.topName(i);
		if (!block.isEmpty()) {
			Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(block.indexOf('[') >= 0 ? block.substring(0, block.indexOf('[')) : block));
			lines.add(b.getName().getString());
		}
		lines.add(String.format(Locale.ROOT, "X %d   Y %d   Z %d", x, (int) c.top[i], z));
		double d = Math.hypot(x + 0.5 - mc.player.getX(), z + 0.5 - mc.player.getZ());
		lines.add(Component.translatable("maqueta.cursor.distance", RouteTracker.formatDistance(d)).getString());
		int w = 0;
		for (String l : lines) w = Math.max(w, font.width(l));
		int x1 = width - 8, x0 = x1 - w - 16, y1 = height - 12, y0 = y1 - lines.size() * 11 - 8;
		MapUi.panel(g, x0, y0, x1, y1, MapUi.PANEL);
		for (int k = 0; k < lines.size(); k++) {
			g.text(font, lines.get(k), x0 + 8, y0 + 5 + k * 11, k == 0 ? MapUi.TEXT : MapUi.TEXT_DIM, false);
		}
	}

	private void drawHelp(GuiGraphicsExtractor g, Font font) {
		int a = Math.round(helpAlpha * 220);
		String help = Component.translatable("maqueta.map.help").getString();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String part : help.split(" · ")) {
			String next = line.isEmpty() ? part : line + " · " + part;
			if (font.width(next) > width - 220 && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(part);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		int top = height - 30 - lines.size() * 10;
		int maxW = 0;
		for (String hl : lines) maxW = Math.max(maxW, font.width(hl));
		MapUi.panel(g, width / 2 - maxW / 2 - 8, top - 4, width / 2 + maxW / 2 + 8, top + lines.size() * 10 + 2,
			(Math.round(helpAlpha * 200) << 24) | 0x141822);
		for (int i = 0; i < lines.size(); i++) {
			String hl = lines.get(i);
			g.text(font, hl, width / 2 - font.width(hl) / 2, top + i * 10, (a << 24) | 0xE8ECF2, false);
		}
	}

	// --- context menu ---

	private void openMenu(double[] point, int x, int y) {
		MapSession s = MapSession.get();
		if (s == null) return;
		ContextMenu m = new ContextMenu(point, x, y);
		Minecraft mc = Minecraft.getInstance();
		String coords = String.format(Locale.ROOT, "%d %d %d", (int) Math.floor(point[0]), (int) Math.ceil(point[1]), (int) Math.floor(point[2]));
		m.items.add(new MenuItem("maqueta.menu.route", () -> {
			s.route.setDestination(point[0], point[1], point[2], coords);
			showToast(Component.translatable("maqueta.route.planning").getString());
		}));
		m.items.add(new MenuItem("maqueta.menu.waypoint", () -> {
			Waypoint w = new Waypoint(Component.translatable("maqueta.waypoint.default").getString(),
				(int) Math.floor(point[0]), (int) Math.ceil(point[1]), (int) Math.floor(point[2]), s.waypoints.nextColor(), s.dimension, 0);
			mc.gui.setScreen(new WaypointScreen(this, w, true));
		}));
		m.items.add(new MenuItem("maqueta.menu.share", () -> {
			if (mc.player != null) {
				Waypoint w = new Waypoint(coords, (int) Math.floor(point[0]), (int) Math.ceil(point[1]), (int) Math.floor(point[2]), 0xFFFFFF, s.dimension, 0);
				mc.player.connection.sendChat(WaypointShare.toChat(w));
				showToast(Component.translatable("maqueta.menu.shared").getString());
			}
		}));
		m.items.add(new MenuItem("maqueta.menu.copy", () -> {
			mc.keyboardHandler.setClipboard(coords);
			showToast(Component.translatable("maqueta.menu.copied", coords).getString());
		}));
		if (s.route.active()) {
			m.items.add(new MenuItem("maqueta.menu.clearroute", s.route::clear));
		}
		menu = m;
	}

	private int menuWidth(Font font) {
		int w = 0;
		for (MenuItem it : menu.items) w = Math.max(w, font.width(Component.translatable(it.key).getString()));
		return w + 18;
	}

	private void drawMenu(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		if (menu == null) return;
		int w = menuWidth(font), h = menu.items.size() * 14 + 6;
		int x0 = Math.min(menu.x, width - w - 4), y0 = Math.min(menu.y, height - h - 4);
		MapUi.panel(g, x0, y0, x0 + w, y0 + h, 0xF0141822);
		for (int i = 0; i < menu.items.size(); i++) {
			int iy = y0 + 3 + i * 14;
			boolean hot = mouseX >= x0 && mouseX < x0 + w && mouseY >= iy && mouseY < iy + 14;
			if (hot) g.fill(x0 + 2, iy, x0 + w - 2, iy + 14, 0x402F8CFF);
			g.text(font, Component.translatable(menu.items.get(i).key), x0 + 9, iy + 3, hot ? MapUi.TEXT : MapUi.TEXT_DIM, false);
		}
	}

	private boolean clickMenu(double mx, double my) {
		if (menu == null) return false;
		Font font = Minecraft.getInstance().font;
		int w = menuWidth(font), h = menu.items.size() * 14 + 6;
		int x0 = Math.min(menu.x, width - w - 4), y0 = Math.min(menu.y, height - h - 4);
		ContextMenu m = menu;
		menu = null;
		if (mx >= x0 && mx < x0 + w && my >= y0 && my < y0 + h) {
			int i = (int) ((my - y0 - 3) / 14);
			if (i >= 0 && i < m.items.size()) m.items.get(i).action.run();
		}
		return true;
	}

	private void showToast(String text) {
		toast = text;
		toastUntil = System.currentTimeMillis() + 2500;
	}

	// --- input ---

	private double[] pick(MapSession s, double mouseX, double mouseY) {
		float ndcX = (float) (mouseX / width * 2 - 1);
		float ndcY = (float) (1 - mouseY / height * 2);
		Matrix4f vp = RENDERER.viewProj();
		OrbitCamera.Ray ray = OrbitCamera.ray(vp, ndcX, ndcY, RENDERER.zZeroToOne());
		return HeightfieldRaycast.cast(s.store, ray, s.anchorX, s.anchorZ, camera.distance() * 8 + 2000);
	}

	private boolean sideButtonClick(double mx, double my) {
		MapSession s = MapSession.get();
		if (mx < width - 44 || mx >= width - 26) return false;
		if (my >= 74 && my < 92) {
			if (s != null && s.hasRoofLayer()) toggleRoofView(s);
			else if (s != null) s.cave.toggleForced();
			return true;
		}
		if (my >= 96 && my < 114) {
			camera.zoom(0.7f);
			return true;
		}
		if (my >= 116 && my < 134) {
			camera.zoom(1.4f);
			return true;
		}
		if (my >= 160 && my < 178) {
			settingsOpen = !settingsOpen;
			return true;
		}
		if (my >= 138 && my < 156) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				followPlayer = true;
				camera.setTarget(mc.player.getX(), mc.player.getY(), mc.player.getZ());
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MapSession s = MapSession.get();
		if (s == null) return false;
		helpDismissed = true;
		if (clickMenu(event.x(), event.y())) return true;
		if (clickSettings(event.x(), event.y())) return true;
		camera.stopInertia();
		int button = event.buttonInfo().button();
		pressNanos = System.nanoTime();
		dragTravel = 0;
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			if (Math.hypot(event.x() - (width - 30), event.y() - 32) < 23) {
				camera.setYaw(0f);
				return true;
			}
			if (sideButtonClick(event.x(), event.y())) return true;
			for (Hit h : waypointHits) {
				if (Math.abs(event.x() - h.x) < 34 && Math.abs(event.y() - h.y) < 8) {
					if (event.hasShiftDown()) {
						Minecraft.getInstance().gui.setScreen(new WaypointScreen(this, h.waypoint, false));
					} else {
						s.waypoints.setTarget(h.waypoint);
						s.route.setDestination(h.waypoint.x + 0.5, h.waypoint.y, h.waypoint.z + 0.5, h.waypoint.name);
					}
					return true;
				}
			}
			double[] hit = pick(s, event.x(), event.y());
			if (event.hasShiftDown() && hit != null) {
				Waypoint w = new Waypoint(Component.translatable("maqueta.waypoint.default").getString(),
					(int) Math.floor(hit[0]), (int) Math.ceil(hit[1]), (int) Math.floor(hit[2]), s.waypoints.nextColor(), s.dimension, 0);
				Minecraft.getInstance().gui.setScreen(new WaypointScreen(this, w, true));
				return true;
			}
			if (doubleClick && hit != null) {
				followPlayer = false;
				camera.setTarget(hit[0], hit[1], hit[2]);
				camera.zoom(0.55f);
				return true;
			}
		}
		dragging = true;
		dragButton = button;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (!dragging) return false;
		dragTravel += Math.abs(dx) + Math.abs(dy);
		if (dragButton == InputConstants.MOUSE_BUTTON_LEFT) {
			followPlayer = false;
			float blocksPerGui = camera.distance() * (float) Math.tan(Math.toRadians(OrbitCamera.FOV_DEG / 2)) * 2f / height;
			float tiltFactor = 1f / Math.max(0.35f, (float) Math.sin(camera.effectivePitch()));
			camera.panBlocks(-dx * blocksPerGui, -dy * blocksPerGui * tiltFactor);
			lastDx = -dx * blocksPerGui;
			lastDy = -dy * blocksPerGui * tiltFactor;
			lastDragNanos = System.nanoTime();
		} else {
			camera.rotate((float) (dx * 0.008), (float) (dy * 0.006));
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		MapSession s = MapSession.get();
		if (dragging && dragButton == InputConstants.MOUSE_BUTTON_RIGHT && dragTravel < 4
			&& System.nanoTime() - pressNanos < 400_000_000L && s != null) {
			// A right click (not a drag) opens the context menu on the terrain.
			double[] hit = pick(s, event.x(), event.y());
			if (hit != null) openMenu(hit, (int) event.x(), (int) event.y());
		} else if (dragging && dragButton == InputConstants.MOUSE_BUTTON_LEFT && !MaquetaConfig.get().reduceMotion
			&& System.nanoTime() - lastDragNanos < 60_000_000L) {
			camera.fling(lastDx * 22, lastDy * 22);
		}
		dragging = false;
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		MapSession s = MapSession.get();
		if (s == null) return false;
		menu = null;
		float factor = (float) Math.pow(0.85, scrollY);
		double[] hit = pick(s, x, y);
		if (hit != null && !followPlayer) {
			double t = 1 - factor;
			camera.setTarget(camera.goalX() + (hit[0] - camera.goalX()) * t, camera.goalY(), camera.goalZ() + (hit[2] - camera.goalZ()) * t);
		}
		camera.zoom(factor);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		MapSession s = MapSession.get();
		Minecraft mc = Minecraft.getInstance();
		int key = event.key();
		if (key == InputConstants.KEY_ESCAPE && menu != null) {
			menu = null;
			return true;
		}
		if (s != null && mc.player != null) {
			if (key == InputConstants.KEY_SPACE) {
				followPlayer = true;
				camera.setTarget(mc.player.getX(), mc.player.getY(), mc.player.getZ());
				return true;
			}
			if (key == InputConstants.KEY_N) {
				camera.setYaw(0f);
				return true;
			}
			if (key == InputConstants.KEY_C) {
				s.cave.toggleForced();
				return true;
			}
			if (key == InputConstants.KEY_B) {
				Waypoint w = new Waypoint(Component.translatable("maqueta.waypoint.default").getString(),
					mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), s.waypoints.nextColor(), s.dimension, 0);
				mc.gui.setScreen(new WaypointScreen(this, w, true));
				return true;
			}
			if (key == InputConstants.KEY_V && MapSession.get() != null && MapSession.get().hasRoofLayer()) {
				toggleRoofView(MapSession.get());
				return true;
			}
			if (key == InputConstants.KEY_H) {
				helpDismissed = !helpDismissed;
				helpAlpha = helpDismissed ? 0f : 1f;
				return true;
			}
			if (key == InputConstants.KEY_EQUALS) {
				camera.zoom(0.8f);
				return true;
			}
			if (key == InputConstants.KEY_MINUS) {
				camera.zoom(1.25f);
				return true;
			}
			if (MaquetaClient.OPEN_MAP.matches(event)) {
				onClose();
				return true;
			}
		}
		return super.keyPressed(event);
	}
}
