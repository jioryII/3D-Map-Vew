package dev.maqueta.core.camera;

import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;

/** Ray march against the explored heightfield: used for click-to-place, fly-to and zoom-to-cursor. */
public final class HeightfieldRaycast {
	private HeightfieldRaycast() {
	}

	/**
	 * @return world coordinates {x, y, z} of the first surface hit, or null.
	 */
	public static double[] cast(ColumnStore store, OrbitCamera.Ray ray, double anchorX, double anchorZ, float maxDistance) {
		float t = 0f;
		float prevX = ray.origin().x, prevY = ray.origin().y, prevZ = ray.origin().z;
		while (t < maxDistance) {
			float step = Math.max(0.35f, t * 0.003f);
			t += step;
			float px = ray.origin().x + ray.dir().x * t;
			float py = ray.origin().y + ray.dir().y * t;
			float pz = ray.origin().z + ray.dir().z * t;
			int wx = (int) Math.floor(px + anchorX);
			int wz = (int) Math.floor(pz + anchorZ);
			int h = store.top(wx, wz);
			if (h != ChunkColumns.UNKNOWN && py <= h) {
				// Refine between previous and current sample.
				float lo = 0f, hi = 1f;
				for (int i = 0; i < 8; i++) {
					float mid = (lo + hi) * 0.5f;
					float mx = prevX + (px - prevX) * mid, my = prevY + (py - prevY) * mid, mz = prevZ + (pz - prevZ) * mid;
					int mh = store.top((int) Math.floor(mx + anchorX), (int) Math.floor(mz + anchorZ));
					if (mh != ChunkColumns.UNKNOWN && my <= mh) hi = mid;
					else lo = mid;
				}
				float fx = prevX + (px - prevX) * hi, fy = prevY + (py - prevY) * hi, fz = prevZ + (pz - prevZ) * hi;
				return new double[]{fx + anchorX, fy, fz + anchorZ};
			}
			if (py < -128 && ray.dir().y < 0) break;
			prevX = px;
			prevY = py;
			prevZ = pz;
		}
		return null;
	}
}
