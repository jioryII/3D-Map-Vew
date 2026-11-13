package dev.maqueta.core.mesh;

import java.nio.ByteBuffer;

/** Immutable result of a mesher run, ready to be uploaded to the GPU. */
public record MeshData(ByteBuffer vertices, int vertexCount, float minY, float maxY) {
	public static final MeshData EMPTY = new MeshData(ByteBuffer.allocateDirect(0), 0, 0, 0);

	public static MeshData of(MeshBuilder builder) {
		if (builder.isEmpty()) {
			return EMPTY;
		}
		return new MeshData(builder.finish(), builder.vertexCount(), builder.minY(), builder.maxY());
	}

	public boolean isEmpty() {
		return vertexCount == 0;
	}

	public int indexCount() {
		return vertexCount / 4 * 6;
	}
}
