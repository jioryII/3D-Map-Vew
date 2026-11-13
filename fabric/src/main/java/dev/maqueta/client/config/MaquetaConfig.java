package dev.maqueta.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.maqueta.core.hud.HudAnchor;
import dev.maqueta.core.io.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Player settings, stored as readable JSON in config/maqueta.json. */
public final class MaquetaConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static MaquetaConfig instance = new MaquetaConfig();

	// Minimap
	public boolean minimapEnabled = true;
	public HudAnchor anchor = HudAnchor.TOP_RIGHT;
	public int minimapSize = 132;
	public int offsetX = 6;
	public int offsetY = 6;
	public boolean rotateWithPlayer = true;
	public float minimapDistance = 72f;
	public float minimapPitchDeg = 58f;
	public boolean showCompass = true;
	public float veilOpacity = 0.28f;

	// HUD extras
	public boolean showSunMoon = true;
	public boolean twelveHourClock = false;
	public boolean showDayCounter = true;
	public boolean showCoordinates = true;

	// Markers
	public boolean showTrail = true;
	public boolean showEntities = true;
	public boolean showHostile = true;
	public boolean showPassive = true;
	public boolean showPlayers = true;
	public int maxFigures = 96;
	public boolean showWaypointBeacons = true;

	// Map
	public boolean autoTilt = true;
	public boolean reduceMotion = false;
	public float supersampling = 1.5f;
	public int renderRadiusChunks = 48;

	// Fair play (can be forced by the server)
	public boolean safeMode = false;

	public static MaquetaConfig get() {
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("maqueta.json");
	}

	public static void load() {
		Path f = file();
		if (Files.exists(f)) {
			try {
				MaquetaConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), MaquetaConfig.class);
				if (c != null) instance = c;
			} catch (Exception ignored) {
				instance = new MaquetaConfig();
			}
		}
		instance.sanitize();
	}

	public void save() {
		sanitize();
		try {
			AtomicFiles.write(file(), GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
		} catch (Exception ignored) {
		}
	}

	private void sanitize() {
		if (anchor == null) anchor = HudAnchor.TOP_RIGHT;
		minimapSize = Math.max(64, Math.min(320, minimapSize));
		minimapDistance = Math.max(16f, Math.min(400f, minimapDistance));
		minimapPitchDeg = Math.max(25f, Math.min(89f, minimapPitchDeg));
		supersampling = Math.max(1f, Math.min(2f, supersampling));
		renderRadiusChunks = Math.max(8, Math.min(160, renderRadiusChunks));
		veilOpacity = Math.max(0f, Math.min(0.8f, veilOpacity));
	}
}
