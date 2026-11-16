package dev.maqueta.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.maqueta.core.mesh.MeshData;

/** An immutable vertex buffer on the GPU holding one baked chunk mesh. */
public final class GpuMesh implements AutoCloseable {
	public final GpuBuffer buffer;
	public final int indexCount;
	public final float minY, maxY;
	public final long bytes;
	public long lastUsedFrame;
	float distanceSquared;

	private GpuMesh(GpuBuffer buffer, int indexCount, float minY, float maxY, long bytes) {
		this.buffer = buffer;
		this.indexCount = indexCount;
		this.minY = minY;
		this.maxY = maxY;
		this.bytes = bytes;
	}

	public static GpuMesh upload(String label, MeshData data) {
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data.vertices());
		return new GpuMesh(buffer, data.indexCount(), data.minY(), data.maxY(), data.vertices().remaining());
	}

	@Override
	public void close() {
		buffer.close();
	}
}
