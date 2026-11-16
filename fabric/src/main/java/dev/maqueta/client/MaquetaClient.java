package dev.maqueta.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maqueta.client.config.MaquetaConfig;
import dev.maqueta.client.hud.MinimapHud;
import dev.maqueta.client.screen.ConfigScreen;
import dev.maqueta.client.screen.MapScreen;
import dev.maqueta.client.screen.WaypointScreen;
import dev.maqueta.client.world.BlockInfo;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.core.marker.Waypoint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MaquetaClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("maqueta");
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("maqueta", "maqueta"));

	public static KeyMapping OPEN_MAP;
	public static KeyMapping ADD_WAYPOINT;
	public static KeyMapping TOGGLE_MINIMAP;
	public static KeyMapping OPEN_CONFIG;
	public static KeyMapping ZOOM_MINIMAP;

	@Override
	public void onInitializeClient() {
		MaquetaConfig.load();
		OPEN_MAP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maqueta.map", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY));
		ADD_WAYPOINT = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maqueta.waypoint", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY));
		TOGGLE_MINIMAP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maqueta.minimap", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		OPEN_CONFIG = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maqueta.config", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY));
		ZOOM_MINIMAP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maqueta.zoom", InputConstants.Type.KEYBOARD, InputConstants.KEY_TAB, CATEGORY));

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			MapSession s = MapSession.forLevel(level);
			if (s != null) s.onChunkLoad(level, chunk);

		});

		ClientTickEvents.END_CLIENT_TICK.register(MaquetaClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MapSession.shutdown());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MapSession.stop());

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			MapSession s = MapSession.get();
			if (s != null) {
				s.applyServerCodes(message.getString());
				receiveShared(s, message.getString());
			}
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
			MapSession s = MapSession.get();
			Minecraft mc = Minecraft.getInstance();
			if (s == null || (sender != null && mc.player != null && sender.id().equals(mc.player.getUUID()))) return;
			receiveShared(s, message.getString());
		});

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("maqueta", "minimap"), MinimapHud::extract);
		LOG.info("Maqueta ready: GPU maqueta map on the {} render API", "renderpearl");
	}

	/** A location shared in chat (Xaero's, JourneyMap or Maqueta format) appears on the map. */
	private static void receiveShared(MapSession s, String text) {
		Waypoint w = dev.maqueta.client.world.WaypointShare.fromChat(text, s.dimension);
		if (w == null) return;
		for (Waypoint e : s.waypoints.all()) {
			if (e.x == w.x && e.z == w.z && e.dimension.equals(w.dimension)) return;
		}
		s.waypoints.add(w);
		Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("maqueta.share.received", w.name), false);
	}

	private static void tick(Minecraft mc) {
		MapSession s = MapSession.sync(mc);
		if (s == null) return;
		s.tick(mc);
		while (OPEN_MAP.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(new MapScreen());
		}
		while (ADD_WAYPOINT.consumeClick()) {
			if (mc.gui.screen() == null && mc.player != null) {
				Waypoint w = new Waypoint(Component.translatable("maqueta.waypoint.default").getString(),
					mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), s.waypoints.nextColor(), s.dimension, 0);
				mc.gui.setScreen(new WaypointScreen(null, w, true));
			}
		}
		while (TOGGLE_MINIMAP.consumeClick()) {
			MaquetaConfig c = MaquetaConfig.get();
			c.minimapEnabled = !c.minimapEnabled;
			mc.gui.hud.setOverlayMessage(Component.translatable(c.minimapEnabled ? "maqueta.minimap.on" : "maqueta.minimap.off"), false);
			c.save();
		}
		while (ZOOM_MINIMAP.consumeClick()) {
			if (mc.gui.screen() == null) {
				MaquetaConfig c = MaquetaConfig.get();
				c.minimapZoomIndex = (c.minimapZoomIndex + 1) % c.minimapZoomLevels.length;
				c.save();
				mc.gui.hud.setOverlayMessage(Component.translatable("maqueta.minimap.zoom", Math.round(c.minimapZoomLevels[c.minimapZoomIndex])), false);
			}
		}
		while (OPEN_CONFIG.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(new ConfigScreen(null));
		}
	}

	static {
		// Resource reloads can change tags; drop cached block classification.
		BlockInfo.clearCache();
	}
}
