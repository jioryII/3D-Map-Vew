package dev.maqueta.core.route;

import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;
import dev.maqueta.core.Material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Route finding over the explored world, like a navigation app but for blocks.
 * <p>
 * A* on columns with eight neighbours. A step can climb at most one block (you can jump
 * one, not two) and drop at most {@link Options#maxDrop} blocks (no fall damage). Water is
 * swimmable but slower, lava and unexplored land are never entered. Paths and farmland are
 * preferred. The result is then string-pulled into a smooth line that still only crosses
 * walkable ground.
 */
public final class RoutePlanner {
	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	public record Options(int maxDrop, float swimCost, int maxNodes, int margin) {
		public static final Options WALK = new Options(3, 2.4f, 700_000, 96);
		/** Mounted: horses cannot swim well; a little more tolerant drops are fine on foot only. */
		public static final Options MOUNTED = new Options(3, 6f, 700_000, 96);
	}

	/** A found route: smoothed points {x, y, z} (block centres) and how it splits by terrain. */
	public record Route(List<double[]> points, double length, double waterLength, boolean complete) {
		public boolean isEmpty() {
			return points.size() < 2;
		}
	}

	private RoutePlanner() {
	}

	/** Things you walk through: plants, torches, snow layers, carpets. */
	private static boolean passThrough(Material m) {
		return m == Material.PLANT || m == Material.EMISSIVE || m == Material.SNOW;
	}

	/**
	 * Height you stand on in a column, or UNKNOWN: the ground, raised by whatever solid stack
	 * rests directly on it (a bush, a log, a wall). The one-block climb limit then decides
	 * whether that stack can be stepped onto (a bush, yes; a trunk or a wall, no).
	 * Water resting on the ground means swimming at its surface.
	 */
	public static int standHeight(ColumnStore store, int x, int z) {
		ChunkColumns c = store.get(x >> 4, z >> 4);
		if (c == null) return ChunkColumns.UNKNOWN;
		int i = ChunkColumns.index(x & 15, z & 15);
		if (!c.isKnown(i)) return ChunkColumns.UNKNOWN;
		int stand = c.floor[i];
		for (int k = c.runCount(i) - 1; k >= 0; k--) {
			if (c.runBottom(i, k) != stand) break;
			Material m = c.runMaterial(i, k);
			if (passThrough(m)) continue;
			stand = c.runTop(i, k);
		}
		return stand;
	}

	private static float terrainCost(ColumnStore store, int x, int z, float swimCost) {
		ChunkColumns c = store.get(x >> 4, z >> 4);
		if (c == null) return Float.POSITIVE_INFINITY;
		int i = ChunkColumns.index(x & 15, z & 15);
		if (!c.isKnown(i)) return Float.POSITIVE_INFINITY;
		int stand = c.floor[i];
		Material on = c.groundMaterial(i);
		for (int k = c.runCount(i) - 1; k >= 0; k--) {
			if (c.runBottom(i, k) != stand) break;
			Material m = c.runMaterial(i, k);
			if (passThrough(m)) continue;
			stand = c.runTop(i, k);
			on = m;
		}
		if (on == Material.LAVA) return Float.POSITIVE_INFINITY;
		if (on == Material.WATER) return swimCost;
		if (on == Material.FOLIAGE) return 1.6f; // jumping over bushes is slower than walking
		if (c.hasFlag(i, ChunkColumns.FLAG_PATH)) return 0.8f;
		return 1f;
	}

	public static Route find(ColumnStore store, double sx, double sz, double gx, double gz, Options o) {
		int x0 = (int) Math.floor(sx), z0 = (int) Math.floor(sz);
		int x1 = (int) Math.floor(gx), z1 = (int) Math.floor(gz);
		int minX = Math.min(x0, x1) - o.margin(), minZ = Math.min(z0, z1) - o.margin();
		int w = Math.abs(x1 - x0) + 2 * o.margin() + 1, h = Math.abs(z1 - z0) + 2 * o.margin() + 1;
		if ((long) w * h > 16_000_000L) return new Route(List.of(), 0, 0, false);

		int[] heights = new int[w * h];
		float[] cost = new float[w * h];
		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				heights[z * w + x] = standHeight(store, minX + x, minZ + z);
				cost[z * w + x] = terrainCost(store, minX + x, minZ + z, o.swimCost());
			}
		}
		int start = (z0 - minZ) * w + (x0 - minX);
		int goal = (z1 - minZ) * w + (x1 - minX);
		if (heights[goal] == ChunkColumns.UNKNOWN) {
			goal = nearestKnown(heights, cost, w, h, goal);
			if (goal < 0) return new Route(List.of(), 0, 0, false);
		}
		if (heights[start] == ChunkColumns.UNKNOWN) return new Route(List.of(), 0, 0, false);

		float[] g = new float[w * h];
		Arrays.fill(g, Float.POSITIVE_INFINITY);
		int[] parent = new int[w * h];
		Arrays.fill(parent, -1);
		boolean[] closed = new boolean[w * h];
		final int gxi = goal % w, gzi = goal / w;
		PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
		g[start] = 0;
		open.add(new long[]{key(heuristic(start % w, start / w, gxi, gzi)), start});
		int expanded = 0;
		int best = start;
		float bestH = heuristic(start % w, start / w, gxi, gzi);
		while (!open.isEmpty() && expanded < o.maxNodes()) {
			int cur = (int) open.poll()[1];
			if (closed[cur]) continue;
			closed[cur] = true;
			expanded++;
			if (cur == goal) break;
			int cx = cur % w, cz = cur / w;
			float hh = heuristic(cx, cz, gxi, gzi);
			if (hh < bestH) {
				bestH = hh;
				best = cur;
			}
			for (int[] s : STEPS) {
				int nx = cx + s[0], nz = cz + s[1];
				if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
				int ni = nz * w + nx;
				if (closed[ni] || !passable(heights, cost, w, cur, ni, o)) continue;
				if (s[0] != 0 && s[1] != 0) {
					// No corner cutting: both orthogonal neighbours must be walkable too.
					if (!passable(heights, cost, w, cur, cz * w + nx, o) || !passable(heights, cost, w, cur, nz * w + cx, o)) continue;
				}
				float step = (s[0] != 0 && s[1] != 0 ? 1.41421f : 1f) * cost[ni];
				int dy = heights[ni] - heights[cur];
				if (dy > 0) step += 0.6f * dy;
				float ng = g[cur] + step;
				if (ng < g[ni]) {
					g[ni] = ng;
					parent[ni] = cur;
					open.add(new long[]{key(ng + heuristic(nx, nz, gxi, gzi) * 0.8f), ni});
				}
			}
		}
		boolean complete = parent[goal] >= 0 || goal == start;
		int end = complete ? goal : best;
		List<int[]> raw = new ArrayList<>();
		for (int c = end; c >= 0; c = parent[c]) {
			raw.add(new int[]{minX + c % w, heights[c], minZ + c / w});
			if (c == start) break;
		}
		Collections.reverse(raw);
		List<double[]> smooth = smooth(raw, heights, cost, w, minX, minZ, o);
		double len = 0, water = 0;
		for (int k = 1; k < smooth.size(); k++) {
			double[] a = smooth.get(k - 1), b = smooth.get(k);
			double d = Math.hypot(b[0] - a[0], b[2] - a[2]);
			len += d;
			int mx = (int) Math.floor((a[0] + b[0]) / 2), mz = (int) Math.floor((a[2] + b[2]) / 2);
			int mi = (mz - minZ) * w + (mx - minX);
			if (mi >= 0 && mi < cost.length && cost[mi] == o.swimCost()) water += d;
		}
		return new Route(smooth, len, water, complete);
	}

	private static long key(float f) {
		return (long) (f * 1024);
	}

	private static float heuristic(int x, int z, int gx, int gz) {
		int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
		return 0.8f * (Math.max(dx, dz) + 0.41421f * Math.min(dx, dz));
	}

	private static boolean passable(int[] heights, float[] cost, int w, int from, int to, Options o) {
		if (heights[to] == ChunkColumns.UNKNOWN || Float.isInfinite(cost[to])) return false;
		int dy = heights[to] - heights[from];
		return dy <= 1 && dy >= -o.maxDrop();
	}

	private static int nearestKnown(int[] heights, float[] cost, int w, int h, int goal) {
		int gx = goal % w, gz = goal / w;
		for (int r = 1; r < 24; r++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int x = gx + dx, z = gz + dz;
					if (x < 0 || z < 0 || x >= w || z >= h) continue;
					int i = z * w + x;
					if (heights[i] != ChunkColumns.UNKNOWN && !Float.isInfinite(cost[i])) return i;
				}
			}
		}
		return -1;
	}

	/** String pulling: keep a point only when the straight line to the next one is not walkable. */
	private static List<double[]> smooth(List<int[]> raw, int[] heights, float[] cost, int w, int minX, int minZ, Options o) {
		List<double[]> out = new ArrayList<>();
		if (raw.isEmpty()) return out;
		int anchor = 0;
		out.add(center(raw.get(0)));
		while (anchor < raw.size() - 1) {
			int next = anchor + 1;
			for (int k = raw.size() - 1; k > anchor + 1; k--) {
				if (k - anchor > 48) continue;
				if (lineWalkable(raw.get(anchor), raw.get(k), heights, cost, w, minX, minZ, o)) {
					next = k;
					break;
				}
			}
			// Keep intermediate points so the drawn line follows the terrain height.
			int[] a = raw.get(anchor), b = raw.get(next);
			int segs = Math.max(1, (int) (Math.hypot(b[0] - a[0], b[2] - a[2]) / 3));
			for (int s = 1; s <= segs; s++) {
				double t = s / (double) segs;
				double x = a[0] + (b[0] - a[0]) * t + 0.5, z = a[2] + (b[2] - a[2]) * t + 0.5;
				int i = ((int) Math.floor(z) - minZ) * w + ((int) Math.floor(x) - minX);
				int y = i >= 0 && i < heights.length && heights[i] != ChunkColumns.UNKNOWN ? heights[i] : b[1];
				out.add(new double[]{x, y, z});
			}
			anchor = next;
		}
		return out;
	}

	private static double[] center(int[] p) {
		return new double[]{p[0] + 0.5, p[1], p[2] + 0.5};
	}

	private static boolean lineWalkable(int[] a, int[] b, int[] heights, float[] cost, int w, int minX, int minZ, Options o) {
		int n = (int) Math.ceil(Math.hypot(b[0] - a[0], b[2] - a[2]) * 2);
		int prev = (a[2] - minZ) * w + (a[0] - minX);
		float baseCost = cost[prev];
		for (int s = 1; s <= n; s++) {
			double t = s / (double) n;
			int x = (int) Math.floor(a[0] + 0.5 + (b[0] - a[0]) * t);
			int z = (int) Math.floor(a[2] + 0.5 + (b[2] - a[2]) * t);
			int i = (z - minZ) * w + (x - minX);
			if (i == prev) continue;
			if (!passable(heights, cost, w, prev, i, o)) return false;
			if (cost[i] > baseCost + 0.5f) return false; // do not shortcut through water
			prev = i;
		}
		return true;
	}
}
