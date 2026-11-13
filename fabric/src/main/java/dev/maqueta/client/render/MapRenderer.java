package dev.maqueta.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.maqueta.client.world.MapSession;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.camera.OrbitCamera;
import dev.maqueta.core.light.LightBaker;
import dev.maqueta.core.light.TimeGrading;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws the maqueta into its own colour + depth texture (design 9.2), which the caller then
 * composites into the GUI with premultiplied alpha. Owning the target and the shaders means
 * shader packs never change how the map looks.
 * <p>
 * Pass order (design 9.3): board/clouds, opaque terrain (front to back), cave slice,
 * depth-tested markers, always-visible markers.
 */
public final class MapRenderer implements AutoCloseable {
	public enum Mode { MINIMAP, FULL_MAP }

	public final OverlayBuilder depthOverlay = new OverlayBuilder();
	public final OverlayBuilder xrayOverlay = new OverlayBuilder();

	private final String label;
	private final FrameUniforms frame;
	private GpuTexture color, depth;
	private GpuTextureView colorView, depthView;
	private MappableRingBuffer overlayRing;
	private int overlayCapacity;
	private Matrix4f viewProj = new Matrix4f();
	private boolean zZeroToOne = true;
	private int width, height;
	private int lastDrawCount;

	public MapRenderer(String label) {
		this.label = label;
		this.frame = new FrameUniforms(label);
	}

	public Matrix4f viewProj() {
		return viewProj;
	}

	public boolean zZeroToOne() {
		return zZeroToOne;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int lastDrawCount() {
		return lastDrawCount;
	}

	private void ensureTargets(int w, int h) {
		if (color != null && width == w && height == h) return;
		closeTargets();
		GpuDevice device = RenderSystem.getDevice();
		int colorUsage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
		int depthUsage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;
		color = device.createTexture(() -> "Maqueta " + label + " colour", colorUsage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
		colorView = device.createTextureView(color);
		depth = device.createTexture(() -> "Maqueta " + label + " depth", depthUsage, GpuFormat.D32_FLOAT, w, h, 1, 1);
		depthView = device.createTextureView(depth);
		width = w;
		height = h;
	}

	/** Computes the camera matrices for this view; call before building overlays that need projection. */
	public Matrix4f prepareCamera(OrbitCamera camera, MapSession session, int w, int h) {
		zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		Matrix4f proj = camera.projection(w / (float) Math.max(1, h), zZeroToOne);
		viewProj = proj.mul(camera.view(session.anchorX, session.anchorZ));
		return viewProj;
	}

	/** Project a world point (absolute coordinates) to pixel coordinates of the target. Returns null if behind. */
	public float[] project(MapSession session, double x, double y, double z) {
		Vector4f p = new Vector4f((float) (x - session.anchorX), (float) y, (float) (z - session.anchorZ), 1f).mul(viewProj);
		if (p.w <= 0.01f) return null;
		float nx = p.x / p.w, ny = p.y / p.w;
		return new float[]{(nx * 0.5f + 0.5f) * width, (1f - (ny * 0.5f + 0.5f)) * height, p.z / p.w};
	}

	public record Params(Mode mode, TimeGrading.Grade grade, float timeSeconds, float fogRadius, float edgeStart, float edgeEnd,
						 float veil, float revealRadius, int radiusChunks, float lodBias) {
	}

	public GpuTextureView render(MapSession session, OrbitCamera camera, int w, int h, Params p) {
		ensureTargets(Math.max(1, w), Math.max(1, h));
		prepareCamera(camera, session, w, h);
		Vector3f eye = camera.eye(session.anchorX, session.anchorZ);
		Vector3f focus = new Vector3f((float) (camera.targetX() - session.anchorX), (float) camera.targetY(), (float) (camera.targetZ() - session.anchorZ));
		boolean caveLayer = session.cave.active() && session.caveLayerAllowed();
		float[] sun = LightBaker.SUN_DIR;
		TimeGrading.Grade g = p.grade();

		GpuBuffer ubo = frame.write(viewProj,
			new Vector4f(eye, p.timeSeconds()),
			new Vector4f(sun[0], sun[1], sun[2], g.nightness()),
			new Vector4f(g.sun()[0], g.sun()[1], g.sun()[2], g.saturation()),
			new Vector4f(g.ambient()[0], g.ambient()[1], g.ambient()[2], g.contrast()),
			new Vector4f(g.emission()[0], g.emission()[1], g.emission()[2], caveLayer ? 0.45f : 0f),
			new Vector4f(g.board()[0], g.board()[1], g.board()[2], session.meshes.boardY()),
			new Vector4f(g.boardDeep()[0], g.boardDeep()[1], g.boardDeep()[2], p.mode() == Mode.MINIMAP ? 1f : 0f),
			new Vector4f(w, h, p.edgeStart(), p.edgeEnd()),
			new Vector4f(focus, p.fogRadius()),
			new Vector4f(caveLayer ? session.cave.cutY() : 1.0e6f, caveLayer ? 1f : 0f, p.veil(), p.revealRadius()));

		// --- visible chunks, nearest first ---
		session.meshes.beginFrame();
		session.meshes.processUploads(1_200_000L);
		session.cave.processUpload();
		FrustumIntersection frustum = new FrustumIntersection(viewProj);
		List<GpuMesh> draws = new ArrayList<>();
		List<Float> dists = new ArrayList<>();
		int fcx = (int) Math.floor(camera.targetX()) >> 4, fcz = (int) Math.floor(camera.targetZ()) >> 4;
		int r = p.radiusChunks();
		int boardY = session.meshes.boardY();
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (dx * dx + dz * dz > r * r) continue;
				int cx = fcx + dx, cz = fcz + dz;
				ChunkColumns cols = session.store.get(cx, cz);
				if (cols == null) continue;
				float x0 = (cx << 4) - session.anchorX, z0 = (cz << 4) - session.anchorZ;
				if (!frustum.testAab(x0, boardY - 1, z0, x0 + 16, 330, z0 + 16)) continue;
				float ccx = x0 + 8 - eye.x, ccz = z0 + 8 - eye.z;
				float dist = (float) Math.sqrt(ccx * ccx + ccz * ccz + (eye.y - 70) * (eye.y - 70) * 0.25f);
				int lod = chooseLod(dist, p.lodBias());
				GpuMesh mesh = session.meshes.request(cx, cz, lod, dist / 16.0);
				if (mesh == null) continue;
				if (!frustum.testAab(x0, mesh.minY - 0.5f, z0, x0 + 16, mesh.maxY + 0.5f, z0 + 16)) continue;
				draws.add(mesh);
				dists.add(dist);
			}
		}
		Integer[] order = new Integer[draws.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Float.compare(dists.get(a), dists.get(b)));

		GpuMesh caveMesh = caveLayer ? session.cave.mesh() : null;
		int maxIndex = caveMesh != null ? caveMesh.indexCount : 6;
		for (GpuMesh m : draws) maxIndex = Math.max(maxIndex, m.indexCount);
		int overlayVerts = Math.max(depthOverlay.vertexCount(), xrayOverlay.vertexCount());
		maxIndex = Math.max(maxIndex, overlayVerts / 4 * 6);
		RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		GpuBuffer indexBuffer = quadIndices.getBuffer(maxIndex);

		GpuBufferSlice depthSlice = null, xraySlice = null;
		int totalOverlay = depthOverlay.bytes() + xrayOverlay.bytes();
		if (totalOverlay > 0) {
			ensureOverlayCapacity(totalOverlay);
			GpuBuffer ob = overlayRing.currentBuffer();
			try (GpuBufferSlice.MappedView view = ob.map(0, totalOverlay, false, true)) {
				view.data().put(depthOverlay.data());
				view.data().put(xrayOverlay.data());
			}
			if (depthOverlay.bytes() > 0) depthSlice = ob.slice(0, depthOverlay.bytes());
			if (xrayOverlay.bytes() > 0) xraySlice = ob.slice(depthOverlay.bytes(), xrayOverlay.bytes());
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		Vector4f clear = p.mode() == Mode.MINIMAP ? new Vector4f(0, 0, 0, 0)
			: new Vector4f(g.board()[0], g.board()[1], g.board()[2], 1f);
		encoder.clearColorAndDepthTextures(color, clear, depth, 0.0);
		int drawCount = 0;
		try (RenderPass pass = encoder.createRenderPass(() -> "Maqueta " + label, colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(MaquetaPipelines.BOARD));
			pass.setUniform(MaquetaPipelines.FRAME_UNIFORM, ubo);
			pass.draw(3, 1, 0, 0);

			pass.setPipeline(RenderSystem.getCompiledPipeline(MaquetaPipelines.TERRAIN));
			pass.setUniform(MaquetaPipelines.FRAME_UNIFORM, ubo);
			pass.setIndexBuffer(indexBuffer, quadIndices.type());
			for (Integer i : order) {
				GpuMesh m = draws.get(i);
				pass.setVertexBuffer(0, m.buffer.slice());
				pass.drawIndexed(m.indexCount, 1, 0, 0, 0);
				drawCount++;
			}
			if (caveMesh != null) {
				pass.setVertexBuffer(0, caveMesh.buffer.slice());
				pass.drawIndexed(caveMesh.indexCount, 1, 0, 0, 0);
				drawCount++;
			}
			if (depthSlice != null) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(MaquetaPipelines.OVERLAY_DEPTH));
				pass.setUniform(MaquetaPipelines.FRAME_UNIFORM, ubo);
				pass.setIndexBuffer(indexBuffer, quadIndices.type());
				pass.setVertexBuffer(0, depthSlice);
				pass.drawIndexed(depthOverlay.vertexCount() / 4 * 6, 1, 0, 0, 0);
				drawCount++;
			}
			if (xraySlice != null) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(MaquetaPipelines.OVERLAY_XRAY));
				pass.setUniform(MaquetaPipelines.FRAME_UNIFORM, ubo);
				pass.setIndexBuffer(indexBuffer, quadIndices.type());
				pass.setVertexBuffer(0, xraySlice);
				pass.drawIndexed(xrayOverlay.vertexCount() / 4 * 6, 1, 0, 0, 0);
				drawCount++;
			}
		}
		frame.rotate();
		if (overlayRing != null && totalOverlay > 0) overlayRing.rotate();
		lastDrawCount = drawCount;
		return colorView;
	}

	private static int chooseLod(float dist, float bias) {
		float d = dist / bias;
		if (d < 150) return 0;
		if (d < 360) return 1;
		if (d < 800) return 2;
		return 3;
	}

	private void ensureOverlayCapacity(int bytes) {
		if (overlayRing != null && overlayCapacity >= bytes) return;
		if (overlayRing != null) overlayRing.close();
		overlayCapacity = Math.max(64 * 1024, Integer.highestOneBit(bytes) << 1);
		overlayRing = new MappableRingBuffer(() -> "Maqueta " + label + " overlay", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, overlayCapacity);
	}

	private void closeTargets() {
		if (colorView != null) colorView.close();
		if (color != null) color.close();
		if (depthView != null) depthView.close();
		if (depth != null) depth.close();
		colorView = null;
		color = null;
		depthView = null;
		depth = null;
	}

	@Override
	public void close() {
		closeTargets();
		frame.close();
		if (overlayRing != null) overlayRing.close();
	}
}
