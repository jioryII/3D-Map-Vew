package dev.maqueta.core.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Growable vertex stream in the maqueta vertex layout (20 bytes, quads of 4 vertices):
 * <pre>
 *   float x, y, z          position relative to the scene anchor
 *   u8 r, g, b, code       albedo + (normal index | flags)
 *   u8 sun, sky, ao, emit  baked light terms (design 3.3)
 * </pre>
 */
public final class MeshBuilder {
	public static final int STRIDE = 20;

	public static final int N_UP = 0, N_DOWN = 1, N_NORTH = 2, N_SOUTH = 3, N_WEST = 4, N_EAST = 5;
	public static final int FLAG_WATER = 8;
	public static final int FLAG_INSIDE = 16;
	public static final int FLAG_BUILT = 32;
	public static final int FLAG_UNLIT = 64;

	private ByteBuffer buffer;
	private int vertices;
	private float minY = Float.POSITIVE_INFINITY;
	private float maxY = Float.NEGATIVE_INFINITY;

	public MeshBuilder(int initialQuads) {
		buffer = ByteBuffer.allocateDirect(Math.max(4, initialQuads) * 4 * STRIDE).order(ByteOrder.nativeOrder());
	}

	public void vertex(float x, float y, float z, int rgb, int code, float sun, float sky, float ao, float emit) {
		ensure(STRIDE);
		buffer.putFloat(x).putFloat(y).putFloat(z);
		buffer.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) code);
		buffer.put(unorm(sun)).put(unorm(sky)).put(unorm(ao)).put(unorm(emit));
		vertices++;
		if (y < minY) minY = y;
		if (y > maxY) maxY = y;
	}

	private static byte unorm(float v) {
		return (byte) Math.round((v < 0 ? 0 : (v > 1 ? 1 : v)) * 255f);
	}

	private void ensure(int bytes) {
		if (buffer.remaining() < bytes) {
			ByteBuffer bigger = ByteBuffer.allocateDirect(buffer.capacity() * 2).order(ByteOrder.nativeOrder());
			buffer.flip();
			bigger.put(buffer);
			buffer = bigger;
		}
	}

	public int vertexCount() {
		return vertices;
	}

	public int quadCount() {
		return vertices / 4;
	}

	public boolean isEmpty() {
		return vertices == 0;
	}

	public float minY() {
		return minY;
	}

	public float maxY() {
		return maxY;
	}

	/** Returns a read-only, flipped view of the written data. The builder must not be reused. */
	public ByteBuffer finish() {
		ByteBuffer out = buffer.duplicate().order(ByteOrder.nativeOrder());
		out.flip();
		return out;
	}
}
