package dev.maqueta.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.maqueta.core.mesh.TileSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The maqueta's own material tiles. Each block face is reinterpreted from the game's model
 * texture as a tiny {@value #TILE}x{@value #TILE} pattern: box-filtered, softened, given a
 * one-texel bevel so every block reads like a carved piece of a scale model, and stored as
 * contrast <em>relative to</em> the face's stylised colour. The palette decides the colour;
 * the tile only adds the material's character (grain, ore specks, bark, leaf gaps).
 * <p>
 * Thread-safe: workers ask for tiles while meshing; the render thread uploads the atlas.
 */
public final class TileAtlas implements TileSource, AutoCloseable {
	public static final int TILE = 4;
	public static final int GRID = 64; // 64 x 64 tiles, 4096 faces
	public static final int SIZE = GRID * TILE;

	private static final TileAtlas INSTANCE = new TileAtlas();

	private record Entry(int tile, int color) {
	}

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();
	private final Map<Identifier, int[]> textures = new ConcurrentHashMap<>();
	private final AtomicInteger next = new AtomicInteger(1);
	private final int[] pixels = new int[SIZE * SIZE];
	private volatile boolean dirty = true;
	private GpuTexture texture;
	private GpuTextureView view;

	public static TileAtlas get() {
		return INSTANCE;
	}

	private TileAtlas() {
		// Tile 0: neutral (no texture), ratio 1.0 everywhere.
		for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) pixels[y * SIZE + x] = encode(1f, 1f, 1f);
	}

	@Override
	public int tile(String block, boolean top) {
		Entry e = entry(block, top);
		return e == null ? 0 : e.tile;
	}

	@Override
	public int color(String block, boolean top) {
		Entry e = entry(block, top);
		return e == null ? -1 : e.color;
	}

	@Override
	public float[][] shape(String block) {
		return dev.maqueta.client.world.BlockShape.boxes(block);
	}

	private Entry entry(String block, boolean top) {
		if (block == null || block.isEmpty()) return null;
		// A log lying on its side shows bark on top, not rings.
		if (top && (block.contains("axis=x") || block.contains("axis=z"))) top = false;
		int bracket = block.indexOf('[');
		if (bracket >= 0) block = block.substring(0, bracket); // the texture does not depend on the state
		String key = block + (top ? "#t" : "#s");
		Entry e = entries.get(key);
		if (e != null) return e.tile < 0 ? null : e;
		e = build(block, top);
		Entry prev = entries.putIfAbsent(key, e);
		if (prev != null) return prev.tile < 0 ? null : prev;
		return e.tile < 0 ? null : e;
	}

	private Entry build(String block, boolean top) {
		try {
			Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(block));
			if (b == null || b == Blocks.AIR) return new Entry(-1, -1);
			int[] argb = composite(b.defaultBlockState(), top ? Direction.UP : Direction.NORTH);
			if (argb == null) return new Entry(-1, -1);
			int id = next.getAndIncrement();
			if (id >= GRID * GRID) return new Entry(-1, -1);
			return new Entry(id, bake(id, argb, edged(block)));
		} catch (Throwable t) {
			return new Entry(-1, -1);
		}
	}

	/** 16x16 (or any size) ARGB of the face as the game draws it, tinted parts made neutral. */
	private int[] composite(BlockState state, Direction face) {
		Minecraft mc = Minecraft.getInstance();
		BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);
		List<BakedQuad> quads = new ArrayList<>();
		for (BlockStateModelPart part : parts) {
			quads.addAll(part.getQuads(face));
			for (BakedQuad q : part.getQuads(null)) if (q.direction() == face) quads.add(q);
		}
		if (quads.isEmpty()) {
			// Cross models (flowers), thin blocks: fall back to the particle texture.
			TextureAtlasSprite sprite = model.particleMaterial().sprite();
			int[] px = load(sprite);
			return px == null ? null : px.clone();
		}
		int[] out = null;
		for (BakedQuad q : quads) {
			int[] px = load(q.materialInfo().sprite());
			if (px == null) continue;
			if (out == null) {
				out = new int[px.length];
			}
			int n = Math.min(out.length, px.length);
			for (int i = 0; i < n; i++) {
				int s = px[i];
				int a = s >>> 24;
				if (a == 0) continue;
				if (q.materialInfo().isTinted()) {
					// Neutral grey keeps the pattern; the biome colour comes from the vertex.
					int l = lum(s);
					s = (a << 24) | (l << 16) | (l << 8) | l;
				}
				int d = out[i];
				float t = a / 255f;
				out[i] = (Math.max(a, d >>> 24) << 24)
					| (mixc(d >> 16, s >> 16, t) << 16) | (mixc(d >> 8, s >> 8, t) << 8) | mixc(d, s, t);
			}
		}
		return out;
	}

	private static int mixc(int a, int b, float t) {
		return Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
	}

	private static int lum(int argb) {
		return Math.round(((argb >> 16) & 0xFF) * 0.299f + ((argb >> 8) & 0xFF) * 0.587f + (argb & 0xFF) * 0.114f);
	}

	/** First animation frame of a sprite's source PNG, as a square ARGB array. */
	private int[] load(TextureAtlasSprite sprite) {
		Identifier name = sprite.contents().name();
		int[] px = textures.computeIfAbsent(name, n -> {
			Identifier file = Identifier.fromNamespaceAndPath(n.getNamespace(), "textures/" + n.getPath() + ".png");
			Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
			if (res.isEmpty()) return new int[0];
			try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
				int w = img.getWidth();
				int[] out = new int[w * w];
				for (int y = 0; y < w; y++) for (int x = 0; x < w; x++) out[y * w + x] = img.getPixel(x, Math.min(y, img.getHeight() - 1));
				return out;
			} catch (Exception e) {
				return new int[0];
			}
		});
		return px.length == 0 ? null : px;
	}

	/** Writes the tile into the atlas and returns the face's average colour. */
	/** Blocks whose edges are part of their look (metal and gem blocks, bricks, glass, planks, tiles). */
	private static boolean edged(String block) {
		String p = block.substring(block.indexOf(':') + 1);
		return p.endsWith("_block") && (p.contains("gold") || p.contains("diamond") || p.contains("emerald") || p.contains("iron")
			|| p.contains("lapis") || p.contains("netherite") || p.contains("copper") || p.contains("redstone") || p.contains("quartz") || p.contains("amethyst"))
			|| p.contains("brick") || p.contains("glass") || p.contains("planks") || p.contains("tiles") || p.contains("bookshelf") || p.contains("chiseled");
	}

	private int bake(int id, int[] src, boolean edged) {
		int w = (int) Math.round(Math.sqrt(src.length));
		int block = Math.max(1, w / TILE);
		float[][] cell = new float[TILE * TILE][3];
		double sr = 0, sg = 0, sb = 0;
		int opaque = 0;
		for (int ty = 0; ty < TILE; ty++) {
			for (int tx = 0; tx < TILE; tx++) {
				float r = 0, g = 0, b = 0, cnt = 0;
				for (int y = ty * block; y < (ty + 1) * block && y < w; y++) {
					for (int x = tx * block; x < (tx + 1) * block && x < w; x++) {
						int p = src[y * w + x];
						float k = (p >>> 24) < 128 ? 0.42f : 1f; // gaps in leaves read as inner shade
						r += ((p >> 16) & 0xFF) * k;
						g += ((p >> 8) & 0xFF) * k;
						b += (p & 0xFF) * k;
						cnt++;
						if ((p >>> 24) >= 128) {
							sr += (p >> 16) & 0xFF;
							sg += (p >> 8) & 0xFF;
							sb += p & 0xFF;
							opaque++;
						}
					}
				}
				cnt = Math.max(1, cnt);
				cell[ty * TILE + tx] = new float[]{r / cnt, g / cnt, b / cnt};
			}
		}
		if (opaque == 0) opaque = 1;
		float ar = (float) (sr / opaque), ag = (float) (sg / opaque), ab = (float) (sb / opaque);
		int gx = (id % GRID) * TILE, gy = (id / GRID) * TILE;
		for (int ty = 0; ty < TILE; ty++) {
			for (int tx = 0; tx < TILE; tx++) {
				float[] c = cell[ty * TILE + tx];
				// Contrast relative to the average, softened for the hand-made look.
				float k = edged ? 0.55f : 0.42f; // natural surfaces: calmer pattern so neighbours blend
				float rr = soften(c[0] / Math.max(1f, ar), k), rg = soften(c[1] / Math.max(1f, ag), k), rb = soften(c[2] / Math.max(1f, ab), k);
				// Bevel: light catches the top-left rim, the bottom-right edge sinks.
				float bevel = !edged ? 1f : (ty == 0 || tx == 0) ? 1.035f : ((ty == TILE - 1 || tx == TILE - 1) ? 0.955f : 1f);
				if (edged && (ty == 0 && tx == TILE - 1 || ty == TILE - 1 && tx == 0)) bevel = 1f;
				pixels[(gy + ty) * SIZE + gx + tx] = encode(rr * bevel, rg * bevel, rb * bevel);
			}
		}
		dirty = true;
		return (Math.round(ar) << 16) | (Math.round(ag) << 8) | Math.round(ab);
	}

	private static float soften(float ratio, float k) {
		float r = 1f + (ratio - 1f) * k;
		return Math.max(0.45f, Math.min(1.75f, r));
	}

	/** RGBA texel: ratio / 2 in RGB so 0..2 fits 0..1; alpha 255 marks a valid tile. */
	private static int encode(float r, float g, float b) {
		int R = Math.round(Math.max(0, Math.min(1, r * 0.5f)) * 255);
		int G = Math.round(Math.max(0, Math.min(1, g * 0.5f)) * 255);
		int B = Math.round(Math.max(0, Math.min(1, b * 0.5f)) * 255);
		return 0xFF000000 | (R << 16) | (G << 8) | B;
	}

	/** Render thread: makes sure the GPU copy is current and returns it. */
	public GpuTextureView view() {
		RenderSystem.assertOnRenderThread();
		if (texture == null) {
			texture = RenderSystem.getDevice().createTexture(() -> "Maqueta tile atlas",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
			view = RenderSystem.getDevice().createTextureView(texture);
			dirty = true;
		}
		if (dirty) {
			dirty = false;
			ByteBuffer buf = MemoryUtil.memAlloc(SIZE * SIZE * 4);
			try {
				for (int p : pixels) {
					buf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
				}
				buf.flip();
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, buf, 0, 0, 0, 0, SIZE, SIZE);
			} finally {
				MemoryUtil.memFree(buf);
			}
		}
		return view;
	}

	/** Forget everything (resource packs changed). Existing meshes keep their ids until rebuilt. */
	public void reset() {
		entries.clear();
		textures.clear();
		next.set(1);
		dirty = true;
	}

	@Override
	public void close() {
		if (view != null) view.close();
		if (texture != null) texture.close();
		view = null;
		texture = null;
	}
}
