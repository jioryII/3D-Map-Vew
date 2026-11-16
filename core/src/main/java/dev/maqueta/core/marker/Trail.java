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
		trimToLength();
	}

	private float maxLength = Float.MAX_VALUE;

	/** Keep only the most recent {@code blocks} of path. */
	public synchronized void setMaxLength(float blocks) {
		maxLength = blocks;
		trimToLength();
	}

	private void trimToLength() {
		float len = 0;
		for (int k = count - 1; k > 0; k--) {
			int a = (start + k) % xs.length, b = (start + k - 1) % xs.length;
			float dx = xs[a] - xs[b], dz = zs[a] - zs[b];
			len += (float) Math.sqrt(dx * dx + dz * dz);
			if (len > maxLength) {
				// Drop everything older than point k-1.
				start = b;
				count = count - (k - 1);
				return;
			}
		}
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
