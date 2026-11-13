package dev.maqueta.client.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** The {@code MaquetaFrame} uniform block, triple-buffered so the CPU never waits on the GPU. */
public final class FrameUniforms implements AutoCloseable {
	public static final int SIZE = new Std140SizeCalculator()
		.putMat4f().putMat4f()
		.putVec4().putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4().putVec4()
		.get();
	/** Number of vec4 fields after the two matrices; must match frame.glsl. */
	public static final int VEC4_COUNT = 10;

	private final MappableRingBuffer ring;

	public FrameUniforms(String label) {
		// 128 = USAGE_UNIFORM, 2 = USAGE_MAP_WRITE
		this.ring = new MappableRingBuffer(() -> "Maqueta frame " + label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, SIZE);
	}

	public GpuBuffer write(Matrix4f viewProj, Vector4f... vec4s) {
		if (vec4s.length != VEC4_COUNT) throw new IllegalArgumentException("MaquetaFrame needs " + VEC4_COUNT + " vec4");
		GpuBuffer buffer = ring.currentBuffer();
		Matrix4f inv = new Matrix4f(viewProj).invert();
		try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
			Std140Builder b = Std140Builder.intoBuffer(view.data()).putMat4f(viewProj).putMat4f(inv);
			for (Vector4f v : vec4s) b.putVec4(v);
		}
		return buffer;
	}

	/** Call once the frame's render pass using the buffer has been recorded. */
	public void rotate() {
		ring.rotate();
	}

	@Override
	public void close() {
		ring.close();
	}
}
