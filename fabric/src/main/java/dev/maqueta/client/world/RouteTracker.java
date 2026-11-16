package dev.maqueta.client.world;

import dev.maqueta.core.route.RoutePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import java.util.List;
import java.util.Locale;

/**
 * Navigation like a maps app: a destination, the best walkable route over the explored
 * world, and an arrival time that follows how you are actually travelling (on foot,
 * sprinting, swimming, on a horse or in a boat). Re-plans when you leave the route.
 */
public final class RouteTracker {
	public static final double WALK = 4.317, SPRINT = 5.612, SWIM = 2.2, BOAT = 8.0;

	public enum Mode { WALK, SPRINT, MOUNT, BOAT }

	private final MapSession session;
	private volatile RoutePlanner.Route route;
	private volatile boolean planning;
	private double[] destination;
	private String label = "";
	private int ticksSincePlan;

	RouteTracker(MapSession session) {
		this.session = session;
	}

	public void setDestination(double x, double y, double z, String name) {
		destination = new double[]{x, y, z};
		label = name;
		route = null;
		ticksSincePlan = 1000;
	}

	public void clear() {
		destination = null;
		route = null;
	}

	public boolean active() {
		return destination != null;
	}

	public double[] destination() {
		return destination;
	}

	public String label() {
		return label;
	}

	public RoutePlanner.Route route() {
		return route;
	}

	public boolean planning() {
		return planning;
	}

	void tick(Minecraft mc) {
		if (destination == null || mc.player == null) return;
		Player p = mc.player;
		double dx = destination[0] - p.getX(), dz = destination[2] - p.getZ();
		if (dx * dx + dz * dz < 5 * 5) {
			mc.gui.hud.setOverlayMessage(Component.translatable("maqueta.route.arrived", label), false);
			clear();
			return;
		}
		ticksSincePlan++;
		boolean offRoute = route != null && distanceToRoute(p.getX(), p.getZ()) > 8;
		if (!planning && (route == null || offRoute || ticksSincePlan > 200)) {
			plan(p);
		}
	}

	private void plan(Player p) {
		planning = true;
		ticksSincePlan = 0;
		double sx = p.getX(), sz = p.getZ();
		double[] dest = destination.clone();
		RoutePlanner.Options opts = mode(p) == Mode.MOUNT ? RoutePlanner.Options.MOUNTED : RoutePlanner.Options.WALK;
		Workers.submit(-2, () -> {
			try {
				RoutePlanner.Route r = RoutePlanner.find(session.store, sx, sz, dest[0], dest[2], opts);
				if (destination != null) route = r;
			} finally {
				planning = false;
			}
		});
	}

	/** Remaining length along the route from the closest point to the player. */
	public double remaining(double px, double pz) {
		RoutePlanner.Route r = route;
		if (r == null || r.isEmpty()) return destination == null ? 0 : Math.hypot(destination[0] - px, destination[2] - pz);
		List<double[]> pts = r.points();
		int best = 0;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < pts.size(); i++) {
			double d = Math.hypot(pts.get(i)[0] - px, pts.get(i)[2] - pz);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		double len = bestD;
		for (int i = best + 1; i < pts.size(); i++) {
			len += Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[2] - pts.get(i - 1)[2]);
		}
		return len;
	}

	private double distanceToRoute(double px, double pz) {
		RoutePlanner.Route r = route;
		if (r == null) return 0;
		double best = Double.MAX_VALUE;
		for (double[] q : r.points()) best = Math.min(best, Math.hypot(q[0] - px, q[2] - pz));
		return best;
	}

	public static Mode mode(Player p) {
		Entity v = p.getVehicle();
		if (v instanceof AbstractBoat) return Mode.BOAT;
		if (v instanceof LivingEntity) return Mode.MOUNT;
		return p.isSprinting() ? Mode.SPRINT : Mode.WALK;
	}

	/** Blocks per second for the current way of travelling. */
	public static double speed(Player p) {
		Entity v = p.getVehicle();
		if (v instanceof AbstractBoat) return BOAT;
		if (v instanceof AbstractHorse h) return Math.max(4.0, h.getAttributeValue(Attributes.MOVEMENT_SPEED) * 42.16);
		if (v instanceof LivingEntity l) return Math.max(2.0, l.getAttributeValue(Attributes.MOVEMENT_SPEED) * 42.16);
		return p.isSprinting() ? SPRINT : WALK;
	}

	/** Estimated seconds to arrival for the given land speed. */
	public double eta(double px, double pz, double landSpeed) {
		RoutePlanner.Route r = route;
		double rem = remaining(px, pz);
		double water = r == null || r.length() <= 0 ? 0 : r.waterLength() * (rem / r.length());
		return (rem - water) / landSpeed + water / (landSpeed >= BOAT ? BOAT : SWIM);
	}

	public static String formatTime(double seconds) {
		long s = Math.max(0, Math.round(seconds));
		if (s < 60) return s + " s";
		if (s < 3600) return String.format(Locale.ROOT, "%d min %02d s", s / 60, s % 60);
		return String.format(Locale.ROOT, "%d h %02d min", s / 3600, (s % 3600) / 60);
	}

	public static String formatDistance(double blocks) {
		if (blocks < 1000) return Math.round(blocks) + " m";
		return String.format(Locale.ROOT, "%.1f km", blocks / 1000.0);
	}
}
