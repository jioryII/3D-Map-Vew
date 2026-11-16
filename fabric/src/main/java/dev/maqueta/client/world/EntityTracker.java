package dev.maqueta.client.world;

import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.core.ChunkColumns;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Nearby creatures, refreshed once per tick (not per frame, not per view) with one spatial
 * query around the player. Each one is classified by layer so the map only shows what
 * belongs to where you are:
 * <ul>
 *   <li>on the surface: creatures standing on or above the ground; cave dwellers are hidden;</li>
 *   <li>in a cave: only creatures down there, near your level;</li>
 *   <li>high up (a tower, a cliff, flying): those at your altitude stand out, the ones on the
 *       ground below are drawn dimmed.</li>
 * </ul>
 */
public final class EntityTracker {
	public static final int HOSTILE = 0xE5534B, PASSIVE = 0x7CC66B, NEUTRAL = 0xF2C14E, PLAYER = 0x56A8F5;
	private static final double RANGE = 160;

	/** A creature to draw; {@code emphasis} 1 = at your level, lower = drawn dimmed. */
	public record Tracked(Entity entity, int color, float emphasis, double x, double y, double z) {
	}

	private final MapSession session;
	private volatile List<Tracked> tracked = List.of();

	EntityTracker(MapSession session) {
		this.session = session;
	}

	public List<Tracked> tracked() {
		return tracked;
	}

	private int ticks;

	void tick(Minecraft mc) {
		if (ticks++ % 4 != 0) return; // positions are interpolated every frame anyway
		MaquetaConfig cfg = MaquetaConfig.get();
		Player self = mc.player;
		if (mc.level == null || self == null || !cfg.showEntities || !session.radarAllowed()) {
			tracked = List.of();
			return;
		}
		boolean cave = session.cave.active() && session.caveLayerAllowed();
		double px = self.getX(), py = self.getY(), pz = self.getZ();
		int playerGround = groundAt(px, pz);
		boolean elevated = !cave && playerGround != ChunkColumns.UNKNOWN && py - playerGround > 6;

		AABB box = new AABB(px - RANGE, py - 96, pz - RANGE, px + RANGE, py + 96, pz + RANGE);
		List<Entity> found = mc.level.getEntities(self, box, e -> e instanceof LivingEntity le && le.isAlive());
		found.sort((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)));
		List<Tracked> out = new ArrayList<>();
		for (Entity e : found) {
			if (out.size() >= cfg.maxFigures) break;
			int color;
			if (e instanceof Player) {
				if (!cfg.showPlayers) continue;
				color = PLAYER;
			} else if (e instanceof Enemy) {
				if (!cfg.showHostile) continue;
				color = HOSTILE;
			} else {
				if (!cfg.showPassive) continue;
				color = e.getType().getCategory().isFriendly() ? PASSIVE : NEUTRAL;
			}
			int ground = groundAt(e.getX(), e.getZ());
			boolean underground = ground != ChunkColumns.UNKNOWN && e.getY() < ground - 2.5;
			float emphasis = 1f;
			if (cave) {
				if (!underground || e.getY() > session.cave.cutY() || Math.abs(e.getY() - py) > 10 || e.distanceTo(self) > 28) continue;
			} else {
				if (underground) continue;
				if (elevated && Math.abs(e.getY() - py) > 4) emphasis = 0.42f;
			}
			out.add(new Tracked(e, color, emphasis, e.getX(), e.getY(), e.getZ()));
		}
		tracked = out;
	}

	private int groundAt(double x, double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		ChunkColumns c = session.store.get(bx >> 4, bz >> 4);
		if (c == null) return ChunkColumns.UNKNOWN;
		int i = ChunkColumns.index(bx & 15, bz & 15);
		return c.isKnown(i) ? c.floor[i] : ChunkColumns.UNKNOWN;
	}
}
