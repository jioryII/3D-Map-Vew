package dev.maqueta.client.render;

import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.marker.Waypoint;
import dev.maqueta.core.route.RoutePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The live layer on top of the maqueta: the player (a dot, a halo and a view cone), the
 * breadcrumb trail, the planned route, waypoint beacons and nearby creatures.
 * <p>
 * Creatures are anchored in 3D (a ring on the ground and a thin stem up to their real height)
 * and their icon is drawn by the GUI at that projected point, so a mob on the tenth floor of a
 * tower sits visibly above one on the first floor instead of overlapping it.
 */
public final class SceneMarkers {
	public static final int HOSTILE = 0xE5534B, PASSIVE = 0x7CC66B, NEUTRAL = 0xF2C14E, PLAYER = 0x56A8F5;
	public static final int ROUTE = 0x2F8CFF;

	public record Label(double x, double y, double z, String text, int color, boolean small) {
	}

	/** A creature icon to draw at a projected 3D point. {@code dy} is its height relative to the player. */
	public record Icon(double x, double y, double z, Entity entity, int color, int dy, double distance, float alpha) {
	}

	public record Result(List<Label> labels, List<Icon> icons) {
	}

	private SceneMarkers() {
	}

	public static Result build(MapRenderer r, MapSession s, Minecraft mc, float partial, float cameraDistance, boolean minimap) {
		r.depthOverlay.reset();
		r.xrayOverlay.reset();
		List<Label> labels = new ArrayList<>();
		List<Icon> icons = new ArrayList<>();
		Player self = mc.player;
		if (self == null) return new Result(labels, icons);
		MaquetaConfig cfg = MaquetaConfig.get();
		float scale = Math.max(0.35f, cameraDistance / (minimap ? 70f : 95f));
		int ax = s.anchorX, az = s.anchorZ;
		Vec3 pos = self.getPosition(partial);
		boolean cave = s.cave.active() && s.caveLayerAllowed();
		float cutY = cave ? s.cave.cutY() : Float.MAX_VALUE;

		// --- trail ---
		if (cfg.showTrail) {
			float[] pts = s.trail.snapshot();
			int n = pts.length / 3;
			float w = 0.3f + scale * 0.3f;
			for (int i = 1; i < n; i++) {
				if (pts[i * 3 + 1] > cutY || pts[(i - 1) * 3 + 1] > cutY) continue;
				float age = i / (float) n;
				int a = OverlayBuilder.argb(0xFFF3D6, 0.10f + 0.5f * age * age);
				int b = OverlayBuilder.argb(0xFFF3D6, 0.10f + 0.5f * ((i + 1f) / n) * ((i + 1f) / n));
				float y0 = groundY(s, pts[(i - 1) * 3], pts[(i - 1) * 3 + 1], pts[(i - 1) * 3 + 2]) + 0.08f;
				float y1 = groundY(s, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]) + 0.08f;
				r.depthOverlay.ribbon(pts[(i - 1) * 3] - ax, y0, pts[(i - 1) * 3 + 2] - az, pts[i * 3] - ax, y1, pts[i * 3 + 2] - az, w, a, b);
			}
		}

		// --- route ---
		RoutePlanner.Route route = s.route.route();
		if (s.route.active()) {
			double[] dst = s.route.destination();
			if (route != null && !route.isEmpty()) {
				List<double[]> pts = route.points();
				float w = 0.7f + scale * 0.55f;
				float t = (float) ((System.nanoTime() / 1e9) % 1000.0);
				double along = 0;
				for (int i = 1; i < pts.size(); i++) {
					double[] a = pts.get(i - 1), b = pts.get(i);
					if (a[1] > cutY + 1 || b[1] > cutY + 1) continue;
					float y0 = (float) a[1] + 0.14f, y1 = (float) b[1] + 0.14f;
					float x0 = (float) a[0] - ax, z0 = (float) a[2] - az, x1 = (float) b[0] - ax, z1 = (float) b[2] - az;
					// White casing, then the route colour, then a faint copy that shows through hills.
					r.depthOverlay.ribbon(x0, y0, z0, x1, y1, z1, w * 1.45f, OverlayBuilder.argb(0xFFFFFF, 0.95f), OverlayBuilder.argb(0xFFFFFF, 0.95f));
					r.depthOverlay.ribbon(x0, y0 + 0.01f, z0, x1, y1 + 0.01f, z1, w, OverlayBuilder.argb(ROUTE, 1f), OverlayBuilder.argb(ROUTE, 1f));
					r.xrayOverlay.ribbon(x0, y0, z0, x1, y1, z1, w * 0.9f, OverlayBuilder.argb(ROUTE, 0.5f), OverlayBuilder.argb(ROUTE, 0.5f));
					// Moving chevron dots every few blocks, flowing towards the destination.
					double seg = Math.hypot(b[0] - a[0], b[2] - a[2]);
					double spacing = Math.max(3.0, scale * 4.0);
					double phase = (t * 6.0) % spacing;
					for (double d = (spacing - (along % spacing) + phase) % spacing; d < seg; d += spacing) {
						double k = d / seg;
						float cx = (float) (x0 + (x1 - x0) * k), cz = (float) (z0 + (z1 - z0) * k), cy = (float) (y0 + (y1 - y0) * k) + 0.03f;
						r.depthOverlay.disc(cx, cy, cz, 0f, w * 0.28f, OverlayBuilder.argb(0xFFFFFF, 0.95f), OverlayBuilder.argb(0xFFFFFF, 0.95f), 8);
					}
					along += seg;
				}
			}
			if (dst != null) {
				float x = (float) dst[0] - ax, z = (float) dst[2] - az;
				int gy = s.store.top((int) Math.floor(dst[0]), (int) Math.floor(dst[2]));
				float y = gy == ChunkColumns.UNKNOWN ? (float) dst[1] : gy;
				float hgt = (minimap ? 8f : 14f) * scale + 5f;
				r.xrayOverlay.beam(x, y, z, y + hgt, 0.5f * scale + 0.25f, OverlayBuilder.argb(ROUTE, 0.95f), OverlayBuilder.argb(ROUTE, 0f));
				r.depthOverlay.disc(x, y + 0.1f, z, 0f, 1.8f * scale + 0.8f, OverlayBuilder.argb(ROUTE, 0.6f), OverlayBuilder.argb(ROUTE, 0f), 24);
				float pin = 0.6f * scale + 0.3f;
				r.xrayOverlay.box(x, y + hgt * 0.55f, z, pin, pin, pin, OverlayBuilder.argb(ROUTE, 1f));
				r.xrayOverlay.box(x, y + hgt * 0.55f, z, pin * 0.45f, pin * 1.05f, pin * 0.45f, OverlayBuilder.argb(0xFFFFFF, 1f));
			}
		}

		// --- beacon beams: only active beacons, in the colours of their glass ---
		if (!cave) {
			for (dev.maqueta.client.world.BeaconTracker.Beam bm : s.beacons.beams()) {
				float bx = bm.x() + 0.5f - ax, bz = bm.z() + 0.5f - az;
				float y = bm.y() + 1;
				float w = 0.32f + scale * 0.18f;
				for (int k = 0; k < bm.colors().length; k++) {
					int col = bm.colors()[k] & 0xFFFFFF;
					float h = k == bm.colors().length - 1 ? 320f - y : bm.heights()[k];
					float top = y + Math.max(1f, h);
					r.depthOverlay.beam(bx, y, bz, top, w * 2.6f, OverlayBuilder.argb(col, 0.22f), OverlayBuilder.argb(col, 0.0f));
					r.depthOverlay.beam(bx, y, bz, top, w, OverlayBuilder.argb(OverlayBuilder.shade(col | 0xFF000000, 1.25f), 0.95f),
						OverlayBuilder.argb(col, k == bm.colors().length - 1 ? 0.15f : 0.9f));
					y = top;
				}
			}
		}

		// --- waypoints ---
		Waypoint target = s.waypoints.target();
		for (Waypoint wp : s.waypoints.inDimension(s.dimension)) {
			if (wp.isHidden()) continue;
			float x = wp.x + 0.5f - ax, z = wp.z + 0.5f - az;
			float y = wp.y;
			int col = wp.color;
			boolean isTarget = wp == target;
			float height = (minimap ? 10f : 18f) * scale + 6f;
			if (cfg.showWaypointBeacons) {
				r.xrayOverlay.beam(x, y, z, y + height, 0.45f * scale + 0.2f, OverlayBuilder.argb(col, 0.85f), OverlayBuilder.argb(col, 0.0f));
			}
			r.depthOverlay.disc(x, y + 0.06f, z, 0f, 1.6f * scale + 0.6f, OverlayBuilder.argb(col, 0.55f), OverlayBuilder.argb(col, 0f), 20);
			float cap = (isTarget ? 0.75f : 0.55f) * scale + 0.25f;
			r.xrayOverlay.box(x, y + height * 0.55f, z, cap, cap, cap, OverlayBuilder.argb(col, 0.95f));
			if (!minimap || isTarget) {
				labels.add(new Label(wp.x + 0.5, y + height * 0.55f + cap * 2.2f, wp.z + 0.5, wp.name, col, minimap));
			}
		}

		// --- creatures: the per-tick tracker already filtered them by layer and distance ---
		if (cfg.showEntities && s.radarAllowed()) {
			double range = minimap ? Math.min(96, cameraDistance * 1.6) : Math.min(160, cameraDistance * 2.5);
			for (dev.maqueta.client.world.EntityTracker.Tracked t : s.entities.tracked()) {
				Entity e = t.entity();
				if (e.isRemoved()) continue;
				// Near creatures move smoothly every frame; far ones use the last tracker snapshot (cheaper, nobody notices).
				double snapD2 = (t.x() - pos.x) * (t.x() - pos.x) + (t.z() - pos.z) * (t.z() - pos.z);
				Vec3 ep = snapD2 < 48 * 48 ? e.getPosition(partial) : new Vec3(t.x(), t.y(), t.z());
				double dist = ep.distanceTo(pos);
				if (dist > range) continue;
				int col = t.color();
				float alpha = t.emphasis();
				float ex = (float) ep.x - ax, ez = (float) ep.z - az, ey = (float) ep.y;
				float ground = groundY(s, (float) ep.x, ey, (float) ep.z);
				float fs = Math.max(0.45f, scale * 0.8f);
				float headY = ey + Math.max(0.6f, e.getBbHeight());
				// Ring where it stands, and a hairline to the ground if it is above or below it.
				r.depthOverlay.disc(ex, ey + 0.05f, ez, 0.3f * fs, 0.7f * fs, OverlayBuilder.argb(col, 0.9f * alpha), OverlayBuilder.argb(col, 0.0f), 12);
				if (Math.abs(ey - ground) > 1.5f) {
					r.xrayOverlay.beam(ex, Math.min(ey, ground), ez, Math.max(ey, ground), 0.07f * fs, OverlayBuilder.argb(col, 0.45f * alpha), OverlayBuilder.argb(col, 0.45f * alpha));
				}
				icons.add(new Icon(ep.x, headY + 0.35f * fs, ep.z, e, col, (int) Math.round(ep.y - pos.y), dist, alpha));
				if (e instanceof Player p && !minimap) {
					labels.add(new Label(ep.x, headY + 1.6f * fs, ep.z, p.getName().getString(), PLAYER, true));
				}
			}
			// Far first, so near icons are drawn on top.
			icons.sort((a, b) -> Double.compare(b.distance(), a.distance()));
		}

		// --- the player: a dot, a halo and a view cone ---
		float px = (float) pos.x - ax, pz = (float) pos.z - az;
		float py = (float) pos.y + 0.12f;
		float yaw = (float) Math.toRadians(self.getViewYRot(partial));
		float ps = Math.max(0.5f, scale * 0.85f);
		r.xrayOverlay.wedge(px, py, pz, yaw, (float) Math.toRadians(32), 9f * ps, OverlayBuilder.argb(0xFFFFFF, 0.42f), 14);
		r.xrayOverlay.disc(px, py, pz, 0f, 2.6f * ps, OverlayBuilder.argb(0xFFFFFF, 0.22f), OverlayBuilder.argb(0xFFFFFF, 0f), 28);
		r.xrayOverlay.disc(px, py + 0.01f, pz, 0.78f * ps, 1.02f * ps, OverlayBuilder.argb(0x10141C, 0.55f), OverlayBuilder.argb(0x10141C, 0.0f), 24);
		r.xrayOverlay.disc(px, py + 0.02f, pz, 0f, 0.8f * ps, OverlayBuilder.argb(0xFFFFFF, 1f), OverlayBuilder.argb(0xFFFFFF, 1f), 24);
		float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw);
		float lx = -fz, lz = fx;
		float tip = 1.75f * ps, base = 0.95f * ps, half = 0.42f * ps;
		r.xrayOverlay.vertex(px + fx * tip, py + 0.03f, pz + fz * tip, OverlayBuilder.argb(0xFFFFFF, 1f));
		r.xrayOverlay.vertex(px + fx * base + lx * half, py + 0.03f, pz + fz * base + lz * half, OverlayBuilder.argb(0xFFFFFF, 1f));
		r.xrayOverlay.vertex(px + fx * (base + 0.15f * ps), py + 0.03f, pz + fz * (base + 0.15f * ps), OverlayBuilder.argb(0xFFFFFF, 1f));
		r.xrayOverlay.vertex(px + fx * base - lx * half, py + 0.03f, pz + fz * base - lz * half, OverlayBuilder.argb(0xFFFFFF, 1f));
		return new Result(labels, icons);
	}

	private static float groundY(MapSession s, float x, float y, float z) {
		int h = s.store.top((int) Math.floor(x), (int) Math.floor(z));
		if (h == ChunkColumns.UNKNOWN || s.cave.active()) return y;
		return Math.abs(h - y) < 6 ? h : y;
	}
}
