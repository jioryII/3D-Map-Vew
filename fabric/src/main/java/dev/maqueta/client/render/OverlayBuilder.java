package dev.maqueta.client.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Per-frame marker geometry (16-byte vertices: position + RGBA), always emitted as quads.
 * Coordinates are relative to the scene anchor, like the terrain.
 */
public final class OverlayBuilder {
	public static final int STRIDE = 16;
	private ByteBuffer buf = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
	private int vertices;

	public void reset() {
		buf.clear();
		vertices = 0;
	}

	public int vertexCount() {
		return vertices;
	}

	public int bytes() {
		return vertices * STRIDE;
	}

	public ByteBuffer data() {
		ByteBuffer d = buf.duplicate().order(ByteOrder.nativeOrder());
		d.flip();
		return d;
	}

	private void ensure() {
		if (buf.remaining() < STRIDE * 4) {
			ByteBuffer b = ByteBuffer.allocateDirect(buf.capacity() * 2).order(ByteOrder.nativeOrder());
			buf.flip();
			b.put(buf);
			buf = b;
		}
	}

	public void vertex(float x, float y, float z, int argb) {
		ensure();
		buf.putFloat(x).putFloat(y).putFloat(z);
		buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
		vertices++;
	}

	public void quad(float[] a, float[] b, float[] c, float[] d, int argb) {
		vertex(a[0], a[1], a[2], argb);
		vertex(b[0], b[1], b[2], argb);
		vertex(c[0], c[1], c[2], argb);
		vertex(d[0], d[1], d[2], argb);
	}

	/** Flat horizontal disc with a radial alpha gradient from {@code inner} to {@code outer} colour. */
	public void disc(float cx, float y, float cz, float r0, float r1, int innerArgb, int outerArgb, int segments) {
		for (int i = 0; i < segments; i++) {
			double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			vertex(cx + c0 * r0, y, cz + s0 * r0, innerArgb);
			vertex(cx + c0 * r1, y, cz + s0 * r1, outerArgb);
			vertex(cx + c1 * r1, y, cz + s1 * r1, outerArgb);
			vertex(cx + c1 * r0, y, cz + s1 * r0, innerArgb);
		}
	}

	/** View cone ("where am I looking") fanning out from a point, fading to nothing. */
	public void wedge(float cx, float y, float cz, float yawRad, float halfAngle, float length, int argb, int segments) {
		int clear = argb & 0x00FFFFFF;
		for (int i = 0; i < segments; i++) {
			double a0 = yawRad - halfAngle + (2 * halfAngle) * i / segments;
			double a1 = yawRad - halfAngle + (2 * halfAngle) * (i + 1) / segments;
			vertex(cx, y, cz, argb);
			vertex(cx + (float) Math.sin(-a0) * length, y, cz + (float) Math.cos(a0) * length, clear);
			vertex(cx + (float) Math.sin(-a1) * length, y, cz + (float) Math.cos(a1) * length, clear);
			vertex(cx, y, cz, argb);
		}
	}

	/** Ribbon along the ground between two points. */
	public void ribbon(float x0, float y0, float z0, float x1, float y1, float z1, float width, int argb0, int argb1) {
		float dx = x1 - x0, dz = z1 - z0;
		float len = (float) Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-4f) return;
		float nx = -dz / len * width * 0.5f, nz = dx / len * width * 0.5f;
		vertex(x0 + nx, y0, z0 + nz, argb0);
		vertex(x0 - nx, y0, z0 - nz, argb0);
		vertex(x1 - nx, y1, z1 - nz, argb1);
		vertex(x1 + nx, y1, z1 + nz, argb1);
	}

	/** Shaded box (top lighter, sides darker) for figures and beacon caps. */
	public void box(float cx, float cy, float cz, float hx, float hy, float hz, int argb) {
		int top = shade(argb, 1.12f), side1 = shade(argb, 0.86f), side2 = shade(argb, 0.72f), bottom = shade(argb, 0.55f);
		float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
		quad(new float[]{x0, y1, z0}, new float[]{x0, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y1, z0}, top);
		quad(new float[]{x0, y0, z0}, new float[]{x1, y0, z0}, new float[]{x1, y0, z1}, new float[]{x0, y0, z1}, bottom);
		quad(new float[]{x0, y0, z0}, new float[]{x0, y1, z0}, new float[]{x1, y1, z0}, new float[]{x1, y0, z0}, side1);
		quad(new float[]{x0, y0, z1}, new float[]{x1, y0, z1}, new float[]{x1, y1, z1}, new float[]{x0, y1, z1}, side1);
		quad(new float[]{x0, y0, z0}, new float[]{x0, y0, z1}, new float[]{x0, y1, z1}, new float[]{x0, y1, z0}, side2);
		quad(new float[]{x1, y0, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, new float[]{x1, y0, z1}, side2);
	}

	/** Thin vertical beam (two crossed quads), fading towards the top. */
	public void beam(float x, float y0, float z, float y1, float width, int argbBottom, int argbTop) {
		float w = width * 0.5f;
		vertex(x - w, y0, z, argbBottom);
		vertex(x + w, y0, z, argbBottom);
		vertex(x + w, y1, z, argbTop);
		vertex(x - w, y1, z, argbTop);
		vertex(x, y0, z - w, argbBottom);
		vertex(x, y0, z + w, argbBottom);
		vertex(x, y1, z + w, argbTop);
		vertex(x, y1, z - w, argbTop);
	}

	public static int shade(int argb, float f) {
		int a = argb >>> 24;
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
		int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
		int b = Math.min(255, (int) ((argb & 0xFF) * f));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	public static int argb(int rgb, float alpha) {
		return ((int) (Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | (rgb & 0xFFFFFF);
	}
}
