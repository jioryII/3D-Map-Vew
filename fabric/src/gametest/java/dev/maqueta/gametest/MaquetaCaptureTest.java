package dev.maqueta.gametest;

import dev.maqueta.client.screen.MapScreen;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.client.world.Workers;
import dev.maqueta.core.marker.Waypoint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Drives a real client: creates a world, walks a route, places waypoints, and lets the game
 * take the screenshots of the minimap and the full map (day, night, cave). Nothing here is
 * mocked; the images come from Minecraft's own framebuffer.
 */
public final class MaquetaCaptureTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(System.getProperty("maqueta.seed", "maqueta"));
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
				s.setAllowCommands(true);
			})
			.create()) {
			TestServerContext server = sp.getServer();
			server.runCommand("gamerule send_command_feedback false");
			server.runCommand("gamerule advance_time false");
			sp.getConnection().waitForChunksRender();
			server.runCommand("time set 1500");
			server.runCommand("weather clear");

			int sx = ctx.computeOnClient(mc -> mc.player.getBlockX());
			int sz = ctx.computeOnClient(mc -> mc.player.getBlockZ());

			// Walk a curved route so the trail and newly explored land are real.
			for (int i = 0; i <= 24; i++) {
				int x = sx + i * 4;
				int z = sz + (int) Math.round(Math.sin(i * 0.35) * 18);
				int y = surface(server, x, z);
				server.runCommand("tp @p " + x + " " + (y + 1) + " " + z + " " + (-90 + i * 3) + " 25");
				ctx.waitTicks(6);
			}
			sp.getConnection().waitForChunksRender();
			int px = ctx.computeOnClient(mc -> mc.player.getBlockX());
			int pz = ctx.computeOnClient(mc -> mc.player.getBlockZ());
			int py = surface(server, px, pz);

			int baseY = surface(server, sx, sz);
			int mineY = surface(server, px + 30, pz - 25);
			ctx.runOnClient(mc -> {
				MapSession s = MapSession.get();
				s.waypoints.add(new Waypoint("Base", sx, baseY, sz, 0x3FB6C9, s.dimension, 0));
				Waypoint mine = new Waypoint("Mina", px + 30, mineY, pz - 25, 0xF2A33A, s.dimension, 0);
				s.waypoints.add(mine);
				s.waypoints.setTarget(mine);
			});

			settle(ctx, 160);
			ctx.takeScreenshot("maqueta_01_minimap_day");

			ctx.setScreen(MapScreen::new);
			settle(ctx, 60);
			ctx.runOnClient(mc -> ((MapScreen) mc.gui.screen()).hideHelp());
			ctx.waitTicks(20);
			ctx.takeScreenshot("maqueta_02_map_day");

			view(ctx, 0.9f, 38f, 70f);
			ctx.takeScreenshot("maqueta_03_map_closeup");

			view(ctx, -0.6f, 62f, 420f);
			ctx.takeScreenshot("maqueta_04_map_wide");

			server.runCommand("time set 13200");
			view(ctx, 0.3f, 48f, 160f);
			ctx.takeScreenshot("maqueta_05_map_dusk");

			server.runCommand("time set 18000");
			ctx.waitTicks(30);
			ctx.takeScreenshot("maqueta_06_map_night");

			ctx.setScreen(() -> null);
			ctx.waitTicks(30);
			ctx.takeScreenshot("maqueta_07_minimap_night");

			// Dig a small lit cave below the player and stand in it.
			server.runCommand("time set 6000");
			int cy = py - 22;
			server.runCommand(String.format("fill %d %d %d %d %d %d air", px - 9, cy, pz - 5, px + 9, cy + 3, pz + 5));
			server.runCommand(String.format("fill %d %d %d %d %d %d air", px + 9, cy, pz - 2, px + 18, cy + 2, pz + 2));
			server.runCommand(String.format("setblock %d %d %d torch", px - 8, cy, pz - 4));
			server.runCommand(String.format("setblock %d %d %d torch", px + 8, cy, pz + 4));
			server.runCommand(String.format("setblock %d %d %d torch", px + 17, cy, pz));
			server.runCommand(String.format("tp @p %d %d %d -60 30", px, cy, pz));
			settle(ctx, 120);
			ctx.takeScreenshot("maqueta_08_minimap_cave");

			ctx.setScreen(MapScreen::new);
			settle(ctx, 40);
			ctx.runOnClient(mc -> ((MapScreen) mc.gui.screen()).hideHelp());
			view(ctx, 0.5f, 55f, 55f);
			ctx.takeScreenshot("maqueta_09_map_cave");
			ctx.setScreen(() -> null);
		}
	}

	private static int surface(TestServerContext server, int x, int z) {
		return server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
	}

	/** Wait until background baking is done (or the timeout passes). */
	private static void settle(ClientGameTestContext ctx, int minTicks) {
		ctx.waitTicks(minTicks);
		for (int i = 0; i < 40 && Workers.pending() > 0; i++) ctx.waitTicks(10);
		ctx.waitTicks(10);
	}

	private static void view(ClientGameTestContext ctx, float yaw, float pitchDeg, float distance) {
		ctx.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen m) {
				m.camera().setYaw(yaw);
				m.camera().rotate(0, (float) Math.toRadians(pitchDeg) - m.camera().goalPitch());
				m.camera().setDistance(distance);
				m.hideHelp();
			}
		});
		settle(ctx, 50);
	}
}
