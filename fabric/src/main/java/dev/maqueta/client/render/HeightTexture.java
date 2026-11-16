package dev.maqueta.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.maqueta.core.ChunkColumns;
import dev.maqueta.core.ColumnStore;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Surface heights on the GPU (R32F, {@value #SIZE}x{@value #SIZE}, wrapping like a torus) so the
 * shaders can trace sun rays per pixel: shadows become crisp, follow the 4x4 texel grid and
 * also fall on the table under the maqueta. Unknown columns hold a very low height.
 */
public final class HeightTexture implements AutoCloseable {
	public static final int SIZE = 2048;
	private static final int SLOTS = SIZE / 16;
	public static final float UNKNOWN = -10000f;

	private final ColumnStore store;
	private final long[] owner = new long[SLOTS * SLOTS];
	private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
	private final java.util.Set<Long> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private GpuTexture texture;
	private GpuTextureView view;
	private GpuTexture mask;
	private GpuTextureView maskView;

	/** Explored mask: a fixed window of {@value #MASK}x{@value #MASK} chunks (8192 blocks) centred on the session anchor; nothing wraps. */
	public static final int MASK = 512;
	private final int maskOriginX, maskOriginZ;

	/** Raw per-chunk mask (packed RGBA) inside the window; the GPU gets a blurred copy. */
	private final Map<String, Integer> weatherByBiome = new HashMap<>();
	private final int[] rawMask = new int[MASK * MASK];
	private static final int[] KERNEL = {1, 4, 6, 4, 1};   // 5x5 binomial: round, soft corners

	/** Stores one chunk's raw mask texel and uploads the blurred texels it influences. */
	private void putMask(CommandEncoder enc, int mx, int mz, int weather, ByteBuffer region) {
		int index = mz * MASK + mx;
		if (rawMask[index] == weather) return;
		rawMask[index] = weather;
		int x0 = Math.max(0, mx - 2), z0 = Math.max(0, mz - 2);
		int x1 = Math.min(MASK - 1, mx + 2), z1 = Math.min(MASK - 1, mz + 2);
		region.clear();
		for (int z = z0; z <= z1; z++) {
			for (int x = x0; x <= x1; x++) {
				int r = 0, g = 0, b = 0, a = 0, wsum = 0;
				for (int dz = -2; dz <= 2; dz++) {
					int zz = z + dz;
					if (zz < 0 || zz >= MASK) continue;
					for (int dx = -2; dx <= 2; dx++) {
						int xx = x + dx;
						if (xx < 0 || xx >= MASK) continue;
						int k = KERNEL[dx + 2] * KERNEL[dz + 2];
						int v = rawMask[zz * MASK + xx];
						r += (v >>> 24) * k;
						g += ((v >> 16) & 255) * k;
						b += ((v >> 8) & 255) * k;
						a += (v & 255) * k;
						wsum += k;
					}
				}
				region.put((byte) (r / wsum)).put((byte) (g / wsum)).put((byte) (b / wsum)).put((byte) (a / wsum));
			}
		}
		region.flip();
		enc.writeToTexture(mask, region, 0, 0, x0, z0, x1 - x0 + 1, z1 - z0 + 1);
	}

	public HeightTexture(ColumnStore store, int anchorX, int anchorZ) {
		this.maskOriginX = (anchorX >> 4) - MASK / 2;
		this.maskOriginZ = (anchorZ >> 4) - MASK / 2;
		this.store = store;
		Arrays.fill(owner, Long.MIN_VALUE);
	}

	/** Any thread: this chunk's heights changed. */
	public void markDirty(int chunkX, int chunkZ) {
		long key = ChunkColumns.key(chunkX, chunkZ);
		if (pending.add(key)) queue.add(key);
	}

	/** Render thread: make sure a chunk that is about to be drawn has its heights resident. */
	public void ensure(int chunkX, int chunkZ) {
		long key = ChunkColumns.key(chunkX, chunkZ);
		if (owner[slot(chunkX, chunkZ)] != key && pending.add(key)) queue.add(key);
	}

	private static int slot(int chunkX, int chunkZ) {
		return Math.floorMod(chunkZ, SLOTS) * SLOTS + Math.floorMod(chunkX, SLOTS);
	}

	public GpuTextureView view(int budget) {
		RenderSystem.assertOnRenderThread();
		if (view != null && queue.isEmpty()) return view;
		CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
		if (texture == null) {
			int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
			texture = RenderSystem.getDevice().createTexture(() -> "Maqueta heights",
				usage, GpuFormat.R32_FLOAT, SIZE, SIZE, 1, 1);
			view = RenderSystem.getDevice().createTextureView(texture);
			enc.clearColorTexture(texture, new Vector4f(UNKNOWN, 0, 0, 0));
			mask = RenderSystem.getDevice().createTexture(() -> "Maqueta explored mask",
				usage, GpuFormat.RGBA8_UNORM, MASK, MASK, 1, 1);
			maskView = RenderSystem.getDevice().createTextureView(mask);
			enc.clearColorTexture(mask, new Vector4f());
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer buf = stack.malloc(16 * 16 * Float.BYTES);
			ByteBuffer region = stack.malloc(25 * 4);
			Long key;
			int n = 0;
			while (n < budget && (key = queue.poll()) != null) {
				pending.remove(key);
				int cx = ChunkColumns.keyX(key), cz = ChunkColumns.keyZ(key);
				ChunkColumns c = store.get(cx, cz);
				buf.clear();
				for (int i = 0; i < ChunkColumns.AREA; i++) {
					buf.putFloat(c == null || !c.isKnown(i) ? UNKNOWN : c.top[i]);
				}
				buf.flip();
				enc.writeToTexture(texture, buf, 0, 0, Math.floorMod(cx * 16, SIZE), Math.floorMod(cz * 16, SIZE), 16, 16);
				// One texel per chunk: explored + weather family of its biomes (blended by the GPU's filtering).
				int mx = cx - maskOriginX, mz = cz - maskOriginZ;
				if (mx >= 0 && mz >= 0 && mx < MASK && mz < MASK) putMask(enc, mx, mz, c == null ? 0 : weather(c), region);
				owner[slot(cx, cz)] = c == null ? Long.MIN_VALUE : key;
				n++;
			}
		}
		return view;
	}

	/** Per-chunk mask: R explored, G storm (mountains, oceans), B snow, A sand (deserts, badlands). */
	private int weather(ChunkColumns c) {
		int known = 0, storm = 0, snow = 0, sand = 0;
		for (int i = 0; i < ChunkColumns.AREA; i++) {
			if (!c.isKnown(i)) continue;
			known++;
			int weather = weatherByBiome.computeIfAbsent(c.biomeName(i), HeightTexture::classifyWeather);
			storm += weather & 1;
			snow += (weather >> 1) & 1;
			sand += (weather >> 2) & 1;
		}
		if (known == 0) return 0;
		return 0xFF000000 | (storm * 255 / known) << 16 | (snow * 255 / known) << 8 | (sand * 255 / known);
	}

	private static int classifyWeather(String biome) {
		int flags = 0;
		// The Nether reuses the channels: storm for soul sand, snow for basalt, sand for warped.
		if (biome.contains("peak") || biome.contains("slope") || biome.contains("windswept") || biome.contains("stony")
			|| biome.contains("ocean") || biome.contains("soul_sand")) flags |= 1;
		if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") || biome.contains("grove")
			|| biome.contains("jagged") || biome.contains("basalt")) flags |= 2;
		if (biome.contains("desert") || biome.contains("badlands") || biome.contains("warped")) flags |= 4;
		return flags;
	}

	/** Render thread, after {@link #view}: the per-chunk explored/weather mask. */
	public GpuTextureView maskView() {
		return maskView;
	}

	@Override
	public void close() {
		if (view != null) view.close();
		if (texture != null) texture.close();
		if (maskView != null) maskView.close();
		if (mask != null) mask.close();
		view = null;
		texture = null;
		maskView = null;
		mask = null;
	}
}
