package dev.maqueta.client.render;

import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.marker.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the live layer on top of the maqueta: the player (a dot with a soft halo and a
 * view cone, nothing more), the breadcrumb trail, waypoint beacons and nearby figures.
 * Text labels are returned separately and drawn by the GUI after compositing.
 */
public final class SceneMarkers {
	public static final int HOSTILE = 0xE5534B, PASSIVE = 0x7CC66B, NEUTRAL = 0xF2C14E, PLAYER = 0x56A8F5;

	public record Label(double x, double y, double z, String text, int color, boolean small) {
	}

	private SceneMarkers() {
	}

	public static List<Label> build(MapRenderer r, MapSession s, Minecraft mc, float partial, float cameraDistance, boolean minimap) {
		r.depthOverlay.reset();
		r.xrayOverlay.reset();
		List<Label> labels = new ArrayList<>();
		Player self = mc.player;
		if (self == null) return labels;
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
			float w = 0.35f + scale * 0.35f;
			for (int i = 1; i < n; i++) {
				if (pts[i * 3 + 1] > cutY || pts[(i - 1) * 3 + 1] > cutY) continue;
				float age = i / (float) n;
				int a = OverlayBuilder.argb(0xFFF3D6, 0.12f + 0.55f * age * age);
				int b = OverlayBuilder.argb(0xFFF3D6, 0.12f + 0.55f * ((i + 1f) / n) * ((i + 1f) / n));
				float y0 = groundY(s, pts[(i - 1) * 3], pts[(i - 1) * 3 + 1], pts[(i - 1) * 3 + 2]) + 0.08f;
				float y1 = groundY(s, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]) + 0.08f;
				r.depthOverlay.ribbon(pts[(i - 1) * 3] - ax, y0, pts[(i - 1) * 3 + 2] - az, pts[i * 3] - ax, y1, pts[i * 3 + 2] - az, w, a, b);
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

		// --- figures (only entities the client already has) ---
		if (cfg.showEntities && s.radarAllowed() && mc.level != null) {
			int count = 0;
			double range = minimap ? Math.min(96, cameraDistance * 1.6) : Math.min(160, cameraDistance * 2.5);
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e == self || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
				if (count >= cfg.maxFigures) break;
				Vec3 ep = e.getPosition(partial);
				if (ep.distanceToSqr(pos) > range * range) continue;
				if (cave && (ep.y > cutY || Math.abs(ep.y - pos.y) > 10 || ep.distanceToSqr(pos) > 24 * 24)) continue;
				int col;
				if (e instanceof Player) {
					if (!cfg.showPlayers) continue;
					col = PLAYER;
				} else if (e instanceof Enemy) {
					if (!cfg.showHostile) continue;
					col = HOSTILE;
				} else {
					if (!cfg.showPassive) continue;
					col = e.getType().getCategory().isFriendly() ? PASSIVE : NEUTRAL;
				}
				count++;
				float ex = (float) ep.x - ax, ez = (float) ep.z - az, ey = (float) ep.y;
				float ground = groundY(s, (float) ep.x, ey, (float) ep.z);
				float fs = Math.max(0.45f, scale * 0.8f);
				// Peg: ring on the ground, thin stem, head on top (board-game token).
				r.depthOverlay.disc(ex, Math.min(ey, ground) + 0.05f, ez, 0.35f * fs, 0.75f * fs, OverlayBuilder.argb(col, 0.9f), OverlayBuilder.argb(col, 0.0f), 12);
				float headY = ey + Math.max(0.6f, e.getBbHeight()) + 0.25f * fs;
				if (Math.abs(ey - ground) > 1.5f) {
					r.xrayOverlay.beam(ex, Math.min(ey, ground), ez, Math.max(ey, ground), 0.08f * fs, OverlayBuilder.argb(0xFFFFFF, 0.35f), OverlayBuilder.argb(0xFFFFFF, 0.35f));
				}
				r.depthOverlay.beam(ex, ey, ez, headY, 0.12f * fs, OverlayBuilder.argb(col, 0.9f), OverlayBuilder.argb(col, 0.9f));
				r.depthOverlay.box(ex, headY, ez, 0.32f * fs, 0.32f * fs, 0.32f * fs, OverlayBuilder.argb(col, 1f));
				r.xrayOverlay.box(ex, headY, ez, 0.2f * fs, 0.2f * fs, 0.2f * fs, OverlayBuilder.argb(col, 0.35f));
				if (e instanceof Player p && !minimap) {
					labels.add(new Label(ep.x, headY + 0.8f * fs, ep.z, p.getName().getString(), PLAYER, true));
				}
			}
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
		// Direction tick: a small bright notch at the front of the dot.
		float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw);
		float lx = -fz, lz = fx;
		float tip = 1.75f * ps, base = 0.95f * ps, half = 0.42f * ps;
		r.xrayOverlay.vertex(px + fx * tip, py + 0.03f, pz + fz * tip, OverlayBuilder.argb(0xFFFFFF, 1f));
		r.xrayOverlay.vertex(px + fx * base + lx * half, py + 0.03f, pz + fz * base + lz * half, OverlayBuilder.argb(0xFFFFFF, 1f));
		r.xrayOverlay.vertex(px + fx * (base + 0.15f * ps), py + 0.03f, pz + fz * (base + 0.15f * ps), OverlayBuilder.argb(0xFFFFFF, 1f));
		r.xrayOverlay.vertex(px + fx * base - lx * half, py + 0.03f, pz + fz * base - lz * half, OverlayBuilder.argb(0xFFFFFF, 1f));
		return labels;
	}

	private static float groundY(MapSession s, float x, float y, float z) {
		int h = s.store.top((int) Math.floor(x), (int) Math.floor(z));
		if (h == ChunkColumns.UNKNOWN || s.cave.active()) return y;
		return Math.abs(h - y) < 6 ? h : y;
	}
}
