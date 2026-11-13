package dev.maqueta.core.marker;

import dev.maqueta.core.io.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Waypoints of one world, persisted to {@code waypoints.txt}. */
public final class WaypointStore {
	private final List<Waypoint> waypoints = new CopyOnWriteArrayList<>();
	private final Path file;
	private Waypoint target;

	public WaypointStore(Path file) {
		this.file = file;
	}

	public void load() {
		waypoints.clear();
		if (file == null || !Files.exists(file)) return;
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank() || line.startsWith("#")) continue;
				Waypoint w = Waypoint.parse(line);
				if (w != null) waypoints.add(w);
			}
		} catch (IOException ignored) {
		}
	}

	public void save() {
		if (file == null) return;
		StringBuilder sb = new StringBuilder("# Maqueta waypoints: name;x;y;z;RRGGBB;dimension;flags\n");
		for (Waypoint w : waypoints) sb.append(w.serialize()).append('\n');
		try {
			AtomicFiles.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException ignored) {
		}
	}

	public List<Waypoint> all() {
		return waypoints;
	}

	public List<Waypoint> inDimension(String dimension) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint w : waypoints) if (w.dimension.equals(dimension)) out.add(w);
		return out;
	}

	public void add(Waypoint w) {
		waypoints.add(w);
		save();
	}

	public void remove(Waypoint w) {
		waypoints.remove(w);
		if (target == w) target = null;
		save();
	}

	/** Only one death marker per dimension: the newest replaces the old one. */
	public void setDeath(Waypoint death) {
		waypoints.removeIf(w -> w.isDeath() && w.dimension.equals(death.dimension));
		waypoints.add(death);
		save();
	}

	public Waypoint target() {
		return target;
	}

	public void setTarget(Waypoint w) {
		target = target == w ? null : w;
	}

	public int nextColor() {
		return Waypoint.COLORS[waypoints.size() % Waypoint.COLORS.length];
	}
}
