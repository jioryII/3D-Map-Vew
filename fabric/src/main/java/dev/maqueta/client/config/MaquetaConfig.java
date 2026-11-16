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
	public int configVersion = 0;
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

	/** Minimap reach presets cycled with the zoom key (Tab by default). */
	public float[] minimapZoomLevels = {40f, 72f, 130f};
	public int minimapZoomIndex = 1;
	/** Make what stands between the camera and the player see-through. */
	public boolean seeThrough = true;

	// HUD extras
	public boolean showCoordinates = true;

	// Markers
	public boolean showTrail = true;
	/** How many blocks of path the breadcrumb trail keeps. */
	public int trailLength = 500;
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
	public boolean clouds = true;
	/** 0 = no haze, 1 = default, 2 = thick. */
	public float haze = 1f;
	/** Off: the explored edge is a straight cut hidden under the fog bank (v0.6 default). */
	public boolean edgeRelief = false;
	/** Pause the singleplayer world while the full map is open. */
	public boolean pauseWhenMapOpen = true;
	/** Show areas another map mod (JourneyMap) already charted, until Maqueta sees them itself. */
	public boolean importOtherMaps = true;

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
		if (instance.configVersion < 5) {
			// v0.4 bound the minimap toggle to H, which clashes with MiniHUD and silently turned it off.
			instance.minimapEnabled = true;
		}
		if (instance.configVersion < 6) {
			instance.edgeRelief = false; // straight edges under fog banks replace the ramp
			instance.pauseWhenMapOpen = true;
		}
		if (instance.configVersion < 6) {
			instance.configVersion = 6;
			instance.save();
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
		trailLength = Math.max(50, Math.min(5000, trailLength));
		haze = Math.max(0f, Math.min(2f, haze));
		if (minimapZoomLevels == null || minimapZoomLevels.length == 0) minimapZoomLevels = new float[]{40f, 72f, 130f};
		minimapZoomIndex = Math.floorMod(minimapZoomIndex, minimapZoomLevels.length);
	}
}
