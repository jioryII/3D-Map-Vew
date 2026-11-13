package dev.maqueta.core.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Google-Earth style camera that orbits a point on the ground.
 * <p>
 * Every user input changes a <em>goal</em>; the visible pose eases towards it
 * (critically damped, ~200 ms) so nothing ever snaps. Pan has a short inertia.
 * Zooming out blends the tilt towards top-down; zooming in restores the user's tilt.
 */
public final class OrbitCamera {
	public static final float MIN_PITCH = (float) Math.toRadians(15);
	public static final float MAX_PITCH = (float) Math.toRadians(89.5);
	public static final float MIN_DISTANCE = 6f;
	public static final float MAX_DISTANCE = 9000f;
	public static final float FOV_DEG = 38f;

	// Goals
	private double goalX, goalY, goalZ;
	private float goalYaw, goalPitch = (float) Math.toRadians(52), goalDistance = 120f;
	// Current (smoothed)
	private double x, y, z;
	private float yaw, pitch = goalPitch, distance = goalDistance;
	// Pan inertia, blocks per second
	private double velX, velZ;
	private boolean autoTilt = true;
	private float smoothing = 14f;

	public void snapTo(double tx, double ty, double tz) {
		goalX = x = tx;
		goalY = y = ty;
		goalZ = z = tz;
		velX = velZ = 0;
	}

	public void setTarget(double tx, double ty, double tz) {
		goalX = tx;
		goalY = ty;
		goalZ = tz;
	}

	public void snapPose(float yawRad, float pitchRad, float dist) {
		goalYaw = yaw = yawRad;
		goalPitch = pitch = clampPitch(pitchRad);
		goalDistance = distance = clampDistance(dist);
	}

	public void setSmoothing(float perSecond) {
		this.smoothing = perSecond;
	}

	public void setAutoTilt(boolean autoTilt) {
		this.autoTilt = autoTilt;
	}

	public void update(float dtSeconds) {
		float dt = Math.min(dtSeconds, 0.1f);
		if (velX != 0 || velZ != 0) {
			goalX += velX * dt;
			goalZ += velZ * dt;
			double decay = Math.exp(-dt * 7.0);
			velX *= decay;
			velZ *= decay;
			if (Math.abs(velX) + Math.abs(velZ) < 0.05) velX = velZ = 0;
		}
		float k = 1f - (float) Math.exp(-dt * smoothing);
		x += (goalX - x) * k;
		y += (goalY - y) * k;
		z += (goalZ - z) * k;
		yaw += wrapAngle(goalYaw - yaw) * k;
		pitch += (goalPitch - pitch) * k;
		// Zoom eases in log space so it feels uniform at any scale.
		distance = (float) Math.exp(Math.log(distance) + (Math.log(goalDistance) - Math.log(distance)) * k);
	}

	/** Pan by a screen-space delta expressed in blocks along the camera's ground axes. */
	public void panBlocks(double right, double forward) {
		double s = Math.sin(goalYaw), c = Math.cos(goalYaw);
		goalX += right * c - forward * s;
		goalZ += right * s + forward * c;
	}

	public void fling(double vRight, double vForward) {
		double s = Math.sin(goalYaw), c = Math.cos(goalYaw);
		velX = vRight * c - vForward * s;
		velZ = vRight * s + vForward * c;
	}

	public void stopInertia() {
		velX = velZ = 0;
	}

	public void rotate(float dYaw, float dPitch) {
		goalYaw += dYaw;
		goalPitch = clampPitch(goalPitch + dPitch);
	}

	public void setYaw(float yawRad) {
		goalYaw = yaw + wrapAngle(yawRad - yaw);
	}

	public void zoom(float factor) {
		goalDistance = clampDistance(goalDistance * factor);
	}

	public void setDistance(float dist) {
		goalDistance = clampDistance(dist);
	}

	public void moveTargetY(double ty) {
		goalY = ty;
	}

	private static float clampPitch(float p) {
		return Math.max(MIN_PITCH, Math.min(MAX_PITCH, p));
	}

	private static float clampDistance(float d) {
		return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, d));
	}

	public static float wrapAngle(float a) {
		while (a > Math.PI) a -= (float) (Math.PI * 2);
		while (a < -Math.PI) a += (float) (Math.PI * 2);
		return a;
	}

	/** Effective pitch after the automatic zoom-dependent tilt. */
	public float effectivePitch() {
		if (!autoTilt) return pitch;
		float t = smoothstep(260f, 2400f, distance);
		return pitch + (MAX_PITCH - 0.02f - pitch) * t;
	}

	private static float smoothstep(float a, float b, float v) {
		float t = Math.max(0f, Math.min(1f, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	public double targetX() { return x; }
	public double targetY() { return y; }
	public double targetZ() { return z; }
	public double goalX() { return goalX; }
	public double goalY() { return goalY; }
	public double goalZ() { return goalZ; }
	public float yaw() { return yaw; }
	public float goalYaw() { return goalYaw; }
	public float pitch() { return pitch; }
	public float goalPitch() { return goalPitch; }
	public float distance() { return distance; }
	public float goalDistance() { return goalDistance; }

	/** Unit vector from the target towards the eye. */
	public Vector3f eyeDirection() {
		float p = effectivePitch();
		// yaw = 0 looks north (towards -Z): the eye sits south of the target.
		return new Vector3f((float) (-Math.sin(yaw) * Math.cos(p)), (float) Math.sin(p), (float) (Math.cos(yaw) * Math.cos(p)));
	}

	/** Eye position relative to the scene anchor. */
	public Vector3f eye(double anchorX, double anchorZ) {
		Vector3f d = eyeDirection().mul(distance);
		return new Vector3f((float) (x - anchorX) + d.x, (float) y + d.y, (float) (z - anchorZ) + d.z);
	}

	public Matrix4f view(double anchorX, double anchorZ) {
		Vector3f eye = eye(anchorX, anchorZ);
		Vector3f target = new Vector3f((float) (x - anchorX), (float) y, (float) (z - anchorZ));
		Vector3f up = Math.abs(effectivePitch()) > MAX_PITCH - 0.05f
			? new Vector3f((float) Math.sin(yaw), 0, (float) -Math.cos(yaw))
			: new Vector3f(0, 1, 0);
		return new Matrix4f().lookAt(eye, target, up);
	}

	/** Reverse-Z perspective matching Minecraft 26.x (depth cleared to 0, test GREATER). */
	public Matrix4f projection(float aspect, boolean zZeroToOne) {
		float near = Math.max(0.5f, distance * 0.02f);
		float far = distance * 6f + 3000f;
		return new Matrix4f().setPerspective((float) Math.toRadians(FOV_DEG), aspect, far, near, zZeroToOne);
	}

	/** World-space ray (relative to anchor) through a normalised device coordinate. */
	public static Ray ray(Matrix4f viewProj, float ndcX, float ndcY, boolean zZeroToOne) {
		Matrix4f inv = new Matrix4f(viewProj).invert();
		float nearZ = 1f; // reverse-Z: near plane at depth 1
		float farZ = zZeroToOne ? 0f : -1f;
		Vector4f a = new Vector4f(ndcX, ndcY, nearZ, 1f).mul(inv);
		Vector4f b = new Vector4f(ndcX, ndcY, farZ + 0.0001f, 1f).mul(inv);
		Vector3f origin = new Vector3f(a.x / a.w, a.y / a.w, a.z / a.w);
		Vector3f end = new Vector3f(b.x / b.w, b.y / b.w, b.z / b.w);
		return new Ray(origin, end.sub(origin).normalize());
	}

	public record Ray(Vector3f origin, Vector3f dir) {
		/** Intersection with the horizontal plane y = h, or null. */
		public Vector3f hitPlane(float h) {
			if (Math.abs(dir.y) < 1e-5f) return null;
			float t = (h - origin.y) / dir.y;
			if (t < 0) return null;
			return new Vector3f(dir).mul(t).add(origin);
		}
	}
}
