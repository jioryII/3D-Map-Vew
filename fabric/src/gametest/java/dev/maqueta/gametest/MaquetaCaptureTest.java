package dev.maqueta.gametest;

import com.mojang.blaze3d.platform.InputConstants;
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
 * Drives a real client: creates a world, walks a route, builds a small tower with creatures
 * on different floors, plans a route, and lets the game take the screenshots of the minimap
 * and the full map (day, dusk, night, cave, context menu). Nothing here is mocked; the
 * images come from Minecraft's own framebuffer.
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
			server.runCommand("gamerule spawn_mobs false");
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

			// A small stone tower with a creature on each floor (icons must stack by height).
			int tx = px + 7, tz = pz + 4, ty = surface(server, tx, tz);
			server.runCommand(String.format("fill %d %d %d %d %d %d stone_bricks", tx - 2, ty, tz - 2, tx + 2, ty + 18, tz + 2));
			server.runCommand(String.format("fill %d %d %d %d %d %d air", tx - 1, ty + 1, tz - 1, tx + 1, ty + 17, tz + 1));
			for (int f = 0; f < 3; f++) {
				server.runCommand(String.format("fill %d %d %d %d %d %d oak_planks", tx - 1, ty + f * 6, tz - 1, tx + 1, ty + f * 6, tz + 1));
			}
			server.runCommand(String.format("summon villager %d %d %d {NoAI:1b}", tx, ty + 1, tz));
			server.runCommand(String.format("summon iron_golem %d %d %d {NoAI:1b}", tx, ty + 7, tz));
			server.runCommand(String.format("summon cat %d %d %d {NoAI:1b}", tx, ty + 13, tz));
			server.runCommand(String.format("summon sheep %d %d %d {NoAI:1b}", px - 6, surface(server, px - 6, pz + 3) + 1, pz + 3));
			server.runCommand(String.format("summon cow %d %d %d {NoAI:1b}", px - 9, surface(server, px - 9, pz - 4) + 1, pz - 4));
			server.runCommand(String.format("summon zombie %d %d %d {NoAI:1b,PersistenceRequired:1b}", px + 2, surface(server, px + 2, pz - 9) + 1, pz - 9));

			// Cases from the v0.4 feedback: natural blocks floating in the air (must not be filled
			// down to the ground), an arch, and an active beacon with red glass.
			int fy = ty + 12;
			server.runCommand(String.format("fill %d %d %d %d %d %d stone", tx + 5, fy, tz - 3, tx + 11, fy + 1, tz + 3));
			server.runCommand(String.format("fill %d %d %d %d %d %d sandstone", tx + 7, fy + 2, tz - 1, tx + 9, fy + 2, tz + 1));
			int ay = surface(server, px - 4, pz - 14);
			server.runCommand(String.format("fill %d %d %d %d %d %d stone_bricks", px - 8, ay, pz - 15, px - 8, ay + 6, pz - 13));
			server.runCommand(String.format("fill %d %d %d %d %d %d stone_bricks", px, ay, pz - 15, px, ay + 6, pz - 13));
			server.runCommand(String.format("fill %d %d %d %d %d %d stone_bricks", px - 8, ay + 7, pz - 15, px, ay + 7, pz - 13));
			int bxp = px - 12, bzp = pz + 8, by = surface(server, bxp, bzp);
			server.runCommand(String.format("fill %d %d %d %d %d %d iron_block", bxp - 1, by, bzp - 1, bxp + 1, by, bzp + 1));
			server.runCommand(String.format("setblock %d %d %d beacon", bxp, by + 1, bzp));
			server.runCommand(String.format("setblock %d %d %d red_stained_glass", bxp, by + 2, bzp));

			// v0.6 feedback: cherry blossom must read pink, not grey-white.
			int chx = px - 4, chz = pz + 6, chy = surface(server, chx, chz) + 1;
			server.runCommand(String.format("fill %d %d %d %d %d %d cherry_log", chx, chy, chz, chx, chy + 3, chz));
			server.runCommand(String.format("fill %d %d %d %d %d %d cherry_leaves[persistent=true]", chx - 2, chy + 3, chz - 2, chx + 2, chy + 5, chz + 2));

			int baseY = surface(server, sx, sz);
			int mx = px + 34, mz = pz - 26;
			int mineY = surface(server, mx, mz);
			ctx.runOnClient(mc -> {
				MapSession s = MapSession.get();
				s.waypoints.add(new Waypoint("Base", sx, baseY, sz, 0x3FB6C9, s.dimension, 0));
				Waypoint mine = new Waypoint("Mina", mx, mineY, mz, 0xF2A33A, s.dimension, 0);
				s.waypoints.add(mine);
				s.waypoints.setTarget(mine);
				s.route.setDestination(mx + 0.5, mineY, mz + 0.5, "Mina");
			});

			settle(ctx, 160);
			ctx.takeScreenshot("maqueta_01_minimap_day");

			ctx.setScreen(MapScreen::new);
			settle(ctx, 60);
			ctx.runOnClient(mc -> ((MapScreen) mc.gui.screen()).hideHelp());
			view(ctx, 0.6f, 50f, 110f);
			ctx.getInput().setCursorPos(330, 240);
			ctx.waitTicks(10);
			ctx.takeScreenshot("maqueta_02_map_route");

			view(ctx, 0.9f, 38f, 42f);
			ctx.takeScreenshot("maqueta_03_map_closeup");

			view(ctx, -0.6f, 58f, 380f);
			ctx.takeScreenshot("maqueta_04_map_table");

			view(ctx, -0.9f, 62f, 900f);
			ctx.takeScreenshot("maqueta_04b_map_altitude");

			view(ctx, 2.2f, 40f, 55f);
			ctx.takeScreenshot("maqueta_05_map_tower");

			server.runCommand("time set 12600");
			view(ctx, 0.3f, 48f, 150f);
			ctx.takeScreenshot("maqueta_06_map_dusk");

			server.runCommand("time set 18000");
			ctx.waitTicks(30);
			ctx.takeScreenshot("maqueta_07_map_night");

			// Right-click menu on the terrain.
			server.runCommand("time set 3000");
			view(ctx, 0.3f, 52f, 120f);
			ctx.getInput().setCursorPos(380, 300);
			ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			ctx.waitTicks(10);
			ctx.takeScreenshot("maqueta_08_map_menu");

			ctx.setScreen(() -> null);
			server.runCommand("time set 18000");
			ctx.waitTicks(30);
			ctx.takeScreenshot("maqueta_09_minimap_night");

			// Dig two caves below the player, light one, stand in it.
			server.runCommand("time set 6000");
			int cy = py - 22;
			server.runCommand(String.format("fill %d %d %d %d %d %d air", px - 9, cy, pz - 5, px + 9, cy + 3, pz + 5));
			server.runCommand(String.format("fill %d %d %d %d %d %d air", px + 9, cy, pz - 2, px + 18, cy + 2, pz + 2));
			server.runCommand(String.format("fill %d %d %d %d %d %d air", px - 20, cy - 2, pz + 10, px - 4, cy + 1, pz + 16));
			server.runCommand(String.format("setblock %d %d %d torch", px - 8, cy, pz - 4));
			server.runCommand(String.format("setblock %d %d %d torch", px + 8, cy, pz + 4));
			server.runCommand(String.format("setblock %d %d %d torch", px + 17, cy, pz));
			server.runCommand(String.format("tp @p %d %d %d -60 30", px, cy, pz));
			settle(ctx, 120);
			ctx.takeScreenshot("maqueta_10_minimap_cave");

			ctx.setScreen(MapScreen::new);
			settle(ctx, 40);
			ctx.runOnClient(mc -> ((MapScreen) mc.gui.screen()).hideHelp());
			view(ctx, 0.5f, 55f, 60f);
			ctx.takeScreenshot("maqueta_11_map_cave");
			ctx.setScreen(() -> null);

			// See-through cutaway: the player inside the stone tower, seen from outside.
			server.runCommand(String.format("tp @p %d %d %d 0 20", tx, ty + 1, tz));
			settle(ctx, 80);
			ctx.setScreen(MapScreen::new);
			settle(ctx, 40);
			view(ctx, 1.4f, 38f, 34f);
			ctx.takeScreenshot("maqueta_11b_map_cutaway");
			view(ctx, 1.4f, 62f, 60f);
			ctx.takeScreenshot("maqueta_11c_map_cutaway_high");
			ctx.setScreen(() -> null);

			// The Nether: the whole inside under the removed roof, then the world above the roof.
			server.runCommand("execute in minecraft:the_nether run fill -3 63 -3 3 63 3 netherrack");
			server.runCommand("execute in minecraft:the_nether run fill -3 64 -3 3 68 3 air");
			server.runCommand("execute in minecraft:the_nether run tp @p 0 64 0 30 30");
			settle(ctx, 240);
			sp.getConnection().waitForChunksRender();
			settle(ctx, 60);
			server.runCommand("execute in minecraft:the_nether run fill 4 128 -3 12 129 3 obsidian");
			server.runCommand("execute in minecraft:the_nether run fill 6 130 -1 9 133 1 glowstone");
			settle(ctx, 60);
			ctx.takeScreenshot("maqueta_12_minimap_nether");
			ctx.setScreen(MapScreen::new);
			settle(ctx, 60);
			view(ctx, 0.7f, 52f, 160f);
			ctx.takeScreenshot("maqueta_13_map_nether_inside");
			view(ctx, 0.7f, 66f, 700f);
			ctx.takeScreenshot("maqueta_13b_map_nether_wide");
			settle(ctx, 40);
			ctx.runOnClient(mc -> MapSession.get().setRoofView(true));
			settle(ctx, 80);
			view(ctx, 0.7f, 52f, 70f);
			ctx.takeScreenshot("maqueta_14_map_nether_roof");
			ctx.setScreen(() -> null);

			// The End: islands over a black void, purple veil at the explored border.
			server.runCommand("execute in minecraft:the_end run tp @p 100 70 0 -90 30");
			settle(ctx, 200);
			sp.getConnection().waitForChunksRender();
			settle(ctx, 60);
			ctx.setScreen(MapScreen::new);
			settle(ctx, 60);
			view(ctx, 0.7f, 55f, 220f);
			ctx.takeScreenshot("maqueta_15_map_end");
			view(ctx, 0.7f, 70f, 700f);
			ctx.takeScreenshot("maqueta_16_map_end_wide");
			ctx.setScreen(() -> null);
			// Return far enough away that Minecraft cannot recapture the earlier Overworld chunk.
			server.runCommand("execute in minecraft:overworld run tp @p " + (px + 2048) + " 180 " + pz);
			boolean restored = false;
			for (int i = 0; i < 120 && !restored; i++) {
				ctx.waitTicks(2);
				restored = ctx.computeOnClient(mc -> {
					MapSession session = MapSession.get();
					return session != null && session.dimension.equals("minecraft:overworld")
						&& session.store.contains(px >> 4, pz >> 4);
				});
			}
			if (!restored) throw new AssertionError("Previously explored Overworld terrain did not reload after dimension changes");

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
