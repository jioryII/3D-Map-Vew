package dev.maqueta.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.hud.MinimapHud;
import dev.maqueta.client.render.MapRenderer;
import dev.maqueta.client.render.SceneMarkers;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.client.world.Workers;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.camera.HeightfieldRaycast;
import dev.maqueta.core.camera.OrbitCamera;
import dev.maqueta.core.light.TimeGrading;
import dev.maqueta.core.marker.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full map: the explored world as a maqueta under a free Google-Earth style camera.
 * Does not pause the game. Every input moves a goal; the camera eases towards it.
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
	private double lastDx, lastDy;
	private long lastDragNanos;
	private double[] hover;
	private boolean followPlayer;
	private final List<Hit> waypointHits = new ArrayList<>();
	private float helpAlpha = 1f;
	private boolean helpDismissed;

	private record Hit(Waypoint waypoint, float x, float y) {
	}

	public MapScreen() {
		super(Component.translatable("maqueta.map.title"));
	}

	/** The orbit camera, for scripted views (game tests, other mods via the API). */
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
		double tx = target != null ? target[0] : mc.player.getX();
		double ty = target != null ? target[1] : mc.player.getY();
		double tz = target != null ? target[2] : mc.player.getZ();
		float yaw = pose != null ? pose[0] : (float) Math.toRadians(mc.player.getYRot()) + (float) Math.PI;
		float pitch = pose != null ? pose[1] : (float) Math.toRadians(55);
		float dist = pose != null ? pose[2] : 140f;
		camera.setAutoTilt(MaquetaConfig.get().autoTilt);
		boolean reduce = MaquetaConfig.get().reduceMotion;
		if (first && !reduce) {
			// Opening: start top-down and high, descend onto the saved pose (~900 ms).
			camera.snapTo(tx, ty, tz);
			camera.snapPose(yaw + 0.35f, OrbitCamera.MAX_PITCH, dist * 5f);
			camera.setSmoothing(4.5f);
		} else {
			camera.snapTo(tx, ty, tz);
			camera.snapPose(yaw, pitch, dist * (reduce ? 1f : 1.12f));
			camera.setSmoothing(12f);
		}
		camera.setYaw(yaw);
		camera.rotate(0, pitch - camera.goalPitch());
		camera.setDistance(dist);
		openedAt = System.nanoTime();
		lastNanos = 0;
		fade = 0f;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
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
		// Keep the orbit point resting on the terrain under it.
		int ground = s.store.top((int) Math.floor(camera.goalX()), (int) Math.floor(camera.goalZ()));
		if (ground != ChunkColumns.UNKNOWN && !s.cave.active()) camera.moveTargetY(ground);
		camera.update(dt);

		int guiScale = mc.getWindow().getGuiScale();
		float ss = MaquetaConfig.get().supersampling;
		int pw = Math.max(1, Math.round(width * guiScale * ss));
		int ph = Math.max(1, Math.round(height * guiScale * ss));

		RENDERER.prepareCamera(camera, s, pw, ph);
		List<SceneMarkers.Label> labels = SceneMarkers.build(RENDERER, s, mc, partial, camera.distance(), false);
		TimeGrading.Grade grade = TimeGrading.grade(s.dimensionKind, mc.level.getOverworldClockTime(), mc.level.getRainLevel(1f), mc.level.getThunderLevel(1f));
		int radius = (int) Math.min(MaquetaConfig.get().renderRadiusChunks, Math.ceil(camera.distance() * 3.2 / 16.0) + 6);
		float fogRadius = Math.max(96f, radius * 16f * 0.92f);
		float lodBias = Math.max(0.8f, ph / 1080f);
		MapRenderer.Params params = new MapRenderer.Params(MapRenderer.Mode.FULL_MAP, grade, seconds(), fogRadius, 0, 1, 0,
			camera.distance() * 3f, radius, lodBias);
		GpuTextureView view = RENDERER.render(s, camera, pw, ph, params);
		int a = Math.round(fade * 255);
		g.guiRenderState.addGuiElement(new net.minecraft.client.renderer.state.gui.BlitRenderState(
			net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
			net.minecraft.client.gui.render.TextureSetup.singleTexture(view,
				com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR)),
			new org.joml.Matrix3x2f(g.pose()), 0, 0, width, height, 0f, 1f, 1f, 0f,
			(a << 24) | (a << 16) | (a << 8) | a, g.scissorStack.peek()));

		// Labels.
		Font font = mc.font;
		float toGui = width / (float) pw;
		waypointHits.clear();
		for (SceneMarkers.Label l : labels) {
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

		hover = pick(s, mouseX, mouseY);
		drawChrome(g, font, s, mc, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, partial);
	}

	private static float seconds() {
		return (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000f;
	}

	private void drawPill(GuiGraphicsExtractor g, Font font, String text, float x, float y, int color) {
		int w = font.width(text);
		int x0 = Math.round(x - w / 2f - 4), y0 = Math.round(y - 6);
		g.fill(x0, y0, x0 + w + 8, y0 + 12, 0xA0101420);
		g.fill(x0, y0 + 11, x0 + w + 8, y0 + 12, 0xFF000000 | color);
		g.text(font, text, x0 + 4, y0 + 2, 0xFFF2F4F8, false);
	}

	private void drawChrome(GuiGraphicsExtractor g, Font font, MapSession s, Minecraft mc, int mouseX, int mouseY) {
		// Title + layer.
		String layer = Component.translatable(s.cave.active() ? "maqueta.layer.cave" : "maqueta.layer.surface").getString();
		String dim = s.dimension.substring(s.dimension.indexOf(':') + 1).replace('_', ' ');
		g.fill(8, 8, 8 + Math.max(font.width(layer + " · " + dim), font.width(title.getString())) + 12, 34, 0x90101420);
		g.text(font, title, 14, 12, 0xFFF2F4F8, false);
		g.text(font, layer + " · " + dim, 14, 23, 0xFFAEB7C6, false);

		// Compass rose (click: north up).
		int cx = width - 26, cy = 26;
		g.fill(cx - 15, cy - 15, cx + 16, cy + 16, 0x70101420);
		float yaw = camera.yaw();
		String[] l = {"maqueta.compass.n", "maqueta.compass.e", "maqueta.compass.s", "maqueta.compass.w"};
		for (int i = 0; i < 4; i++) {
			double ang = -yaw + i * Math.PI / 2;
			float x = cx + (float) Math.sin(ang) * 10, y = cy - (float) Math.cos(ang) * 10;
			String t = Component.translatable(l[i]).getString();
			g.text(font, t, Math.round(x - font.width(t) / 2f), Math.round(y - 4), i == 0 ? 0xFFF05A4F : 0xFFE8ECF2, false);
		}

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
		int bx = 12, by = height - 18;
		g.fill(bx, by, bx + bar, by + 2, 0xE0F2F4F8);
		g.fill(bx, by - 3, bx + 1, by + 2, 0xE0F2F4F8);
		g.fill(bx + bar - 1, by - 3, bx + bar, by + 2, 0xE0F2F4F8);
		g.text(font, meters + " m", bx + bar + 5, by - 3, 0xFFE8ECF2, true);

		// Cursor coordinates.
		if (hover != null) {
			String c = String.format(Locale.ROOT, "%d, %d, %d", (int) Math.floor(hover[0]), (int) Math.floor(hover[1]), (int) Math.floor(hover[2]));
			g.text(font, c, width - font.width(c) - 10, height - 20, 0xFFE8ECF2, true);
		}

		// Stats line (helps verify the GPU path).
		String stats = String.format(Locale.ROOT, "%d chunks · %d draws · %.0f MB GPU · %d jobs",
			s.store.size(), RENDERER.lastDrawCount(), s.meshes.gpuBytes() / 1048576.0, Workers.pending());
		g.text(font, stats, width - font.width(stats) - 10, height - 9, 0x90AEB7C6, false);

		if (helpAlpha > 0.01f) {
			int a = Math.round(helpAlpha * 200);
			String help = Component.translatable("maqueta.map.help").getString();
			// Split on the separators into as many centred lines as the width needs.
			List<String> lines = new ArrayList<>();
			StringBuilder line = new StringBuilder();
			for (String part : help.split(" · ")) {
				String next = line.isEmpty() ? part : line + " · " + part;
				if (font.width(next) > width - 40 && !line.isEmpty()) {
					lines.add(line.toString());
					line = new StringBuilder(part);
				} else {
					line = new StringBuilder(next);
				}
			}
			if (!line.isEmpty()) lines.add(line.toString());
			int top = height - 34 - lines.size() * 10;
			int maxW = 0;
			for (String hl : lines) maxW = Math.max(maxW, font.width(hl));
			g.fill(width / 2 - maxW / 2 - 6, top - 3, width / 2 + maxW / 2 + 6, top + lines.size() * 10 + 1, (Math.round(helpAlpha * 150) << 24) | 0x101420);
			for (int i = 0; i < lines.size(); i++) {
				String hl = lines.get(i);
				g.text(font, hl, width / 2 - font.width(hl) / 2, top + i * 10, (a << 24) | 0xE8ECF2, false);
			}
		}
	}

	// --- input ---

	private double[] pick(MapSession s, double mouseX, double mouseY) {
		float ndcX = (float) (mouseX / width * 2 - 1);
		float ndcY = (float) (1 - mouseY / height * 2);
		Matrix4f vp = RENDERER.viewProj();
		OrbitCamera.Ray ray = OrbitCamera.ray(vp, ndcX, ndcY, RENDERER.zZeroToOne());
		return HeightfieldRaycast.cast(s.store, ray, s.anchorX, s.anchorZ, camera.distance() * 8 + 2000);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MapSession s = MapSession.get();
		if (s == null) return false;
		helpDismissed = true;
		helpAlpha = 0f;
		camera.stopInertia();
		int button = event.buttonInfo().button();
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			// Compass rose: north up.
			if (Math.abs(event.x() - (width - 26)) < 16 && Math.abs(event.y() - 26) < 16) {
				camera.setYaw(0f);
				return true;
			}
			for (Hit h : waypointHits) {
				if (Math.abs(event.x() - h.x) < 30 && Math.abs(event.y() - h.y) < 8) {
					if (event.hasShiftDown()) {
						Minecraft.getInstance().gui.setScreen(new WaypointScreen(this, h.waypoint, false));
					} else {
						s.waypoints.setTarget(h.waypoint);
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
		if (dragging && dragButton == InputConstants.MOUSE_BUTTON_LEFT && !MaquetaConfig.get().reduceMotion
			&& System.nanoTime() - lastDragNanos < 60_000_000L) {
			// Short inertia after a fling.
			camera.fling(lastDx * 22, lastDy * 22);
		}
		dragging = false;
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		MapSession s = MapSession.get();
		if (s == null) return false;
		float factor = (float) Math.pow(0.85, scrollY);
		double[] hit = pick(s, x, y);
		if (hit != null && !followPlayer) {
			// Zoom towards the cursor: move the orbit point part of the way to the hit.
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
			if (dev.maqueta.client.MaquetaClient.OPEN_MAP.matches(event)) {
				onClose();
				return true;
			}
		}
		return super.keyPressed(event);
	}
}
