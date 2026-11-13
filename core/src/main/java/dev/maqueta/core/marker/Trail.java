package dev.maqueta.core.marker;

/** Breadcrumb trail: a ring buffer of positions, recorded when the player moved far enough. */
public final class Trail {
	private final float[] xs, ys, zs;
	private int start, count;
	private final float minStep;

	public Trail(int capacity, float minStep) {
		xs = new float[capacity];
		ys = new float[capacity];
		zs = new float[capacity];
		this.minStep = minStep;
	}

	public synchronized void record(double x, double y, double z) {
		if (count > 0) {
			int last = (start + count - 1) % xs.length;
			double dx = x - xs[last], dy = y - ys[last], dz = z - zs[last];
			if (dx * dx + dy * dy + dz * dz < minStep * minStep) return;
			if (dx * dx + dz * dz > 256 * 256) clear(); // teleport: start a fresh trail
		}
		int i = (start + count) % xs.length;
		xs[i] = (float) x;
		ys[i] = (float) y;
		zs[i] = (float) z;
		if (count < xs.length) count++;
		else start = (start + 1) % xs.length;
	}

	public synchronized void clear() {
		start = 0;
		count = 0;
	}

	public synchronized int size() {
		return count;
	}

	/** Copies points oldest-first into {x0,y0,z0,x1,...}. */
	public synchronized float[] snapshot() {
		float[] out = new float[count * 3];
		for (int k = 0; k < count; k++) {
			int i = (start + k) % xs.length;
			out[k * 3] = xs[i];
			out[k * 3 + 1] = ys[i];
			out[k * 3 + 2] = zs[i];
		}
		return out;
	}
}
