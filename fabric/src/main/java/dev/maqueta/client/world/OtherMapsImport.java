package dev.maqueta.client.world;

import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.Material;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Reads what JourneyMap already charted for this world so those areas show up on the
 * maqueta before Maqueta itself has visited them.
 * <p>
 * JourneyMap keeps one 512×512 PNG per region ({@code journeymap/data/sp|mp/<world>/<dimension>/day/<rx>,<rz>.png}),
 * one pixel per block, transparent where nothing was seen. Those images carry colour but no
 * height, so the chunks built from them are flat sketches at sea level: water where the
 * pixel is clearly blue, land a block above it. They are marked {@link ChunkColumns#imported},
 * never written to Maqueta's own files, and replaced as soon as the real chunk is captured.
 */
final class OtherMapsImport {
	private static final Logger LOG = LoggerFactory.getLogger("maqueta");

	private OtherMapsImport() {
	}

	/** The region images to read for this world and dimension, or an empty list. */
	static List<Path> findRegions(Path gameDir, String worldName, String serverIp, String serverName, String dimension) {
		Path data = gameDir.resolve("journeymap").resolve("data");
		Path world = null;
		if (worldName != null) {
			world = match(data.resolve("sp"), worldName);
		} else if (serverIp != null) {
			world = match(data.resolve("mp"), serverIp);
			if (world == null) world = match(data.resolve("mp"), serverName);
		}
		if (world == null) return List.of();
		for (String folder : dimensionFolders(dimension)) {
			Path day = world.resolve(folder).resolve("day");
			if (!Files.isDirectory(day)) continue;
			List<Path> out = new ArrayList<>();
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(day, "*.png")) {
				for (Path p : ds) if (regionOf(p) != null) out.add(p);
			} catch (IOException e) {
				LOG.warn("Could not list JourneyMap tiles in {}", day, e);
			}
			if (!out.isEmpty()) {
				LOG.info("Maqueta: importing {} JourneyMap regions from {}", out.size(), day);
				return out;
			}
		}
		return List.of();
	}

	/** Region coordinates from a file name such as {@code -1,2.png}. */
	static int[] regionOf(Path file) {
		String n = file.getFileName().toString();
		n = n.substring(0, n.length() - 4);
		int comma = n.indexOf(',');
		if (comma < 0) return null;
		try {
			return new int[]{Integer.parseInt(n.substring(0, comma).trim()), Integer.parseInt(n.substring(comma + 1).trim())};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Builds the sketched chunks of one region image; only fully charted chunks are kept. */
	static void read(Path file, int seaLevel, Consumer<ChunkColumns> sink) {
		int[] r = regionOf(file);
		if (r == null) return;
		BufferedImage img;
		try {
			img = ImageIO.read(file.toFile());
		} catch (IOException e) {
			LOG.warn("Could not read JourneyMap tile {}", file, e);
			return;
		}
		if (img == null || img.getWidth() < 512 || img.getHeight() < 512) return;
		int[] px = img.getRGB(0, 0, 512, 512, null, 0, 512);
		for (int cz = 0; cz < 32; cz++) {
			for (int cx = 0; cx < 32; cx++) {
				boolean full = true;
				for (int z = 0; z < 16 && full; z++) {
					for (int x = 0; x < 16; x++) {
						if ((px[(cz * 16 + z) * 512 + cx * 16 + x] >>> 24) < 128) {
							full = false;
							break;
						}
					}
				}
				if (!full) continue;
				ChunkColumns c = new ChunkColumns(r[0] * 32 + cx, r[1] * 32 + cz);
				c.imported = true;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int rgb = px[(cz * 16 + z) * 512 + cx * 16 + x] & 0xFFFFFF;
						Material m = guess(rgb);
						if (m == Material.WATER) {
							c.set(x, z, seaLevel, seaLevel - 3, m, rgb, Material.SAND, 0xC8B488, 0xC8B488, 0, 0);
						} else {
							c.set(x, z, seaLevel + 1, seaLevel + 1, m, rgb, m.subsurface(), darker(rgb), darker(rgb), 0, 0);
						}
					}
				}
				sink.accept(c);
			}
		}
	}

	/** Coarse material from a shaded map colour. */
	static Material guess(int rgb) {
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
		int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		if (b > r + 25 && b >= g - 5) return Material.WATER;
		if (min > 200) return Material.SNOW;
		if (max - min < 22) return max > 150 ? Material.STONE : Material.STONE;
		if (g >= r && g > b) return max < 110 ? Material.FOLIAGE : Material.GRASS;
		if (r > b + 40 && g > b + 25 && max > 170) return Material.SAND;
		if (r > 140 && g < 80 && b < 80) return Material.NETHER;
		return Material.DIRT;
	}

	private static int darker(int rgb) {
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
		return ((r * 3 / 4) << 16) | ((g * 3 / 4) << 8) | (b * 3 / 4);
	}

	private static List<String> dimensionFolders(String dimension) {
		List<String> out = new ArrayList<>();
		switch (dimension) {
			case "minecraft:overworld" -> out.addAll(List.of("overworld", "minecraft~overworld", "DIM0"));
			case "minecraft:the_nether" -> out.addAll(List.of("the_nether", "minecraft~the_nether", "DIM-1"));
			case "minecraft:the_end" -> out.addAll(List.of("the_end", "minecraft~the_end", "DIM1"));
			default -> {
			}
		}
		out.add(dimension.replace(':', '~'));
		out.add(dimension.substring(dimension.indexOf(':') + 1));
		return out;
	}

	/** The child folder whose name matches the world or server name, ignoring case and punctuation. */
	private static Path match(Path dir, String name) {
		if (name == null || !Files.isDirectory(dir)) return null;
		String want = simplify(name);
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
			Path loose = null;
			for (Path p : ds) {
				String have = simplify(p.getFileName().toString());
				if (have.equals(want)) return p;
				if (loose == null && !want.isEmpty() && (have.startsWith(want) || want.startsWith(have)) && have.length() > 2) loose = p;
			}
			return loose;
		} catch (IOException e) {
			return null;
		}
	}

	private static String simplify(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
