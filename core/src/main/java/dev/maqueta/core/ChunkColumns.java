package dev.maqueta.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Source data for one 16x16 chunk column: what the player actually saw, not how it looks.
 * <p>
 * Each column is a <b>layered column</b>: the natural ground (a solid mass down to the
 * bottom) plus, above it, the runs of blocks the player can see with the air between them
 * kept as air. A branch ten blocks up stays ten blocks up; a bridge keeps the gap under it;
 * a log lying sideways is a log lying sideways.
 * <pre>
 *   y=80  [oak_leaves  80..77]   run 0
 *         (air)
 *   y=74  [oak_log     74..70]   run 1
 *   y=69  ground: grass_block (floor = 70), dirt below, stone deeper
 * </pre>
 * Block names in the palette may carry block-state properties
 * ({@code minecraft:oak_stairs[facing=east,half=top]}) for blocks that are not full cubes.
 */
public final class ChunkColumns {
	public static final int SIZE = 16;
	public static final int AREA = SIZE * SIZE;
	public static final short UNKNOWN = Short.MIN_VALUE;

	/** Column is a path, road or farmland: cheaper for routes. */
	public static final int FLAG_PATH = 2;

	public final int chunkX;
	public final int chunkZ;
	/** Top of everything visible in the column (block y + 1), water included. */
	public final short[] top = new short[AREA];
	/** Top of the natural ground (block y + 1). */
	public final short[] floor = new short[AREA];
	/** Material and source colour of the topmost visible thing (used by coarse levels of detail). */
	public final byte[] material = new byte[AREA];
	public final int[] color = new int[AREA];
	/** Ground: its material, source colour and the material just under it (cliff strata). */
	public final byte[] groundMaterial = new byte[AREA];
	public final int[] floorColor = new int[AREA];
	public final byte[] subMaterial = new byte[AREA];
	public final int[] subColor = new int[AREA];
	public final byte[] emission = new byte[AREA];
	/** Small decoration on the ground (flowers, grass tufts); 0 = none. Painted, not modelled. */
	public final int[] decoColor = new int[AREA];
	public final byte[] flags = new byte[AREA];
	/** Indices into {@link #blocks}: topmost block, block below the ground surface, ground block. */
	public final short[] topBlock = new short[AREA];
	public final short[] subBlock = new short[AREA];
	public final short[] floorBlock = new short[AREA];
	public final byte[] biome = new byte[AREA];
	public final List<String> blocks = new ArrayList<>();
	public final List<String> biomes = new ArrayList<>();
	public long capturedAt;

	// Runs above the ground, top-down, stored flat: column i owns [runStart[i], runStart[i] + runCount[i]).
	private final short[] runStart = new short[AREA];
	private final byte[] runCount = new byte[AREA];
	private static final short[] NO_SHORT_RUNS = new short[0];
	private static final byte[] NO_BYTE_RUNS = new byte[0];
	private static final int[] NO_INT_RUNS = new int[0];
	private short[] rTop = NO_SHORT_RUNS;
	private short[] rBottom = NO_SHORT_RUNS;
	private short[] rBlock = NO_SHORT_RUNS;
	private byte[] rMat = NO_BYTE_RUNS;
	private int[] rColor = NO_INT_RUNS;
	private int runs;

	public ChunkColumns(int chunkX, int chunkZ) {
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		Arrays.fill(top, UNKNOWN);
		Arrays.fill(floor, UNKNOWN);
		blocks.add("");
		biomes.add("");
	}

	public static int index(int localX, int localZ) {
		return (localZ << 4) | localX;
	}

	public long key() {
		return key(chunkX, chunkZ);
	}

	public static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	public static int keyX(long key) {
		return (int) (key >> 32);
	}

	public static int keyZ(long key) {
		return (int) key;
	}

	/** Legacy single-surface setter (tests, coarse data): a column with no runs above its ground. */
	public void set(int lx, int lz, int topY, int floorY, Material mat, int rgb, Material sub, int subRgb,
					int floorRgb, int emit, int deco) {
		int i = index(lx, lz);
		top[i] = (short) topY;
		floor[i] = (short) floorY;
		material[i] = (byte) mat.ordinal();
		groundMaterial[i] = (byte) (mat == Material.WATER ? sub.ordinal() : mat.ordinal());
		subMaterial[i] = (byte) sub.ordinal();
		color[i] = rgb & 0xFFFFFF;
		subColor[i] = subRgb & 0xFFFFFF;
		floorColor[i] = floorRgb & 0xFFFFFF;
		emission[i] = (byte) emit;
		decoColor[i] = deco;
		if (mat == Material.WATER && topY > floorY) {
			beginRuns(i);
			addRun(topY, floorY, 0, Material.WATER, rgb);
		}
	}

	/** Starts the runs of column {@code i}. Columns must be filled in index order. */
	public void beginRuns(int i) {
		runStart[i] = (short) runs;
		runCount[i] = 0;
		lastBegun = i;
	}

	/** Adds a run [bottom, top) above the ground. Runs are added top-down. */
	public void addRun(int topY, int bottomY, int block, Material mat, int rgb) {
		if (runs == rTop.length) resizeRuns(Math.max(16, runs * 2));
		rTop[runs] = (short) topY;
		rBottom[runs] = (short) bottomY;
		rBlock[runs] = (short) block;
		rMat[runs] = (byte) mat.ordinal();
		rColor[runs] = rgb & 0xFFFFFF;
		runs++;
		int i = currentColumn();
		runCount[i]++;
	}

	private void resizeRuns(int capacity) {
		rTop = Arrays.copyOf(rTop, capacity);
		rBottom = Arrays.copyOf(rBottom, capacity);
		rBlock = Arrays.copyOf(rBlock, capacity);
		rMat = Arrays.copyOf(rMat, capacity);
		rColor = Arrays.copyOf(rColor, capacity);
	}

	/** Release unused construction capacity before the chunk enters the map cache. */
	public void compact() {
		if (runs <= rTop.length * 3 / 4 && runs < rTop.length) resizeRuns(runs);
		if (voxels != null) voxels.compactShell();
	}

	private int lastBegun = -1;

	private int currentColumn() {
		return lastBegun;
	}

	/** Marks the column whose runs follow; call before {@link #addRun}. */
	public void column(int i) {
		beginRuns(i);
	}

	public int runCount(int i) {
		return runCount[i];
	}

	public int runTop(int i, int k) {
		return rTop[runStart[i] + k];
	}

	public int runBottom(int i, int k) {
		return rBottom[runStart[i] + k];
	}

	public String runBlock(int i, int k) {
		return blocks.get(rBlock[runStart[i] + k] & 0xFFFF);
	}

	public Material runMaterial(int i, int k) {
		return Material.byId(rMat[runStart[i] + k]);
	}

	public int runColor(int i, int k) {
		return rColor[runStart[i] + k];
	}

	/** Names the blocks of a column (registry ids such as {@code minecraft:grass_block}). */
	public void setNames(int lx, int lz, String topName, String subName, String floorName, String biomeName, int columnFlags) {
		int i = index(lx, lz);
		topBlock[i] = (short) blockIndex(topName);
		subBlock[i] = (short) blockIndex(subName);
		floorBlock[i] = (short) blockIndex(floorName);
		biome[i] = (byte) biomeIndex(biomeName);
		flags[i] = (byte) columnFlags;
	}

	public int blockIndex(String name) {
		if (name == null || name.isEmpty()) return 0;
		int k = blocks.indexOf(name);
		if (k >= 0) return k;
		blocks.add(name);
		return blocks.size() - 1;
	}

	private int biomeIndex(String name) {
		if (name == null || name.isEmpty()) return 0;
		int k = biomes.indexOf(name);
		if (k >= 0) return k;
		if (biomes.size() >= 255) return 0;
		biomes.add(name);
		return biomes.size() - 1;
	}

	public String topName(int i) {
		return blocks.get(topBlock[i] & 0xFFFF);
	}

	public String subName(int i) {
		return blocks.get(subBlock[i] & 0xFFFF);
	}

	public String floorName(int i) {
		return blocks.get(floorBlock[i] & 0xFFFF);
	}

	public String biomeName(int i) {
		return biomes.get(biome[i] & 0xFF);
	}

	public boolean hasFlag(int i, int flag) {
		return (flags[i] & flag) != 0;
	}

	public Material material(int i) {
		return Material.byId(material[i]);
	}

	public Material groundMaterial(int i) {
		return Material.byId(groundMaterial[i]);
	}

	public Material subMaterial(int i) {
		return Material.byId(subMaterial[i]);
	}

	public boolean isKnown(int i) {
		return top[i] != UNKNOWN;
	}

	/** Serialised form version. Bump when the layout changes. */
	public static final int FORMAT = 5;

	public void write(DataOutput out) throws IOException {
		out.writeInt(chunkX);
		out.writeInt(chunkZ);
		out.writeLong(capturedAt);
		out.writeShort(blocks.size());
		for (String b : blocks) out.writeUTF(b);
		out.writeByte(biomes.size());
		for (String b : biomes) out.writeUTF(b);
		for (int i = 0; i < AREA; i++) {
			out.writeShort(top[i]);
			out.writeShort(floor[i]);
			out.writeByte(material[i]);
			out.writeByte(groundMaterial[i]);
			out.writeByte(subMaterial[i]);
			out.writeInt(color[i]);
			out.writeInt(subColor[i]);
			out.writeInt(floorColor[i]);
			out.writeByte(emission[i]);
			out.writeInt(decoColor[i]);
			out.writeByte(flags[i]);
			out.writeShort(topBlock[i]);
			out.writeShort(subBlock[i]);
			out.writeShort(floorBlock[i]);
			out.writeByte(biome[i]);
			int n = runCount[i];
			out.writeByte(n);
			for (int k = 0; k < n; k++) {
				int r = runStart[i] + k;
				out.writeShort(rTop[r]);
				out.writeShort(rBottom[r]);
				out.writeShort(rBlock[r]);
				out.writeByte(rMat[r]);
				out.writeInt(rColor[r]);
			}
		}
		out.writeBoolean(voxels != null);
		if (voxels != null) voxels.write(out);
	}

	/** The 3D shell of the chunk (detail level). Null for data imported from elsewhere. */
	public ChunkVoxels voxels;
	/** Sketched from another map mod's tiles: drawn, never saved, replaced when really seen. */
	public transient boolean imported;

	public static ChunkColumns read(DataInput in) throws IOException {
		ChunkColumns c = new ChunkColumns(in.readInt(), in.readInt());
		c.capturedAt = in.readLong();
		c.blocks.clear();
		int nb = in.readUnsignedShort();
		for (int k = 0; k < nb; k++) c.blocks.add(in.readUTF());
		c.biomes.clear();
		int nbi = in.readUnsignedByte();
		for (int k = 0; k < nbi; k++) c.biomes.add(in.readUTF());
		if (c.blocks.isEmpty()) c.blocks.add("");
		if (c.biomes.isEmpty()) c.biomes.add("");
		for (int i = 0; i < AREA; i++) {
			c.top[i] = in.readShort();
			c.floor[i] = in.readShort();
			c.material[i] = in.readByte();
			c.groundMaterial[i] = in.readByte();
			c.subMaterial[i] = in.readByte();
			c.color[i] = in.readInt();
			c.subColor[i] = in.readInt();
			c.floorColor[i] = in.readInt();
			c.emission[i] = in.readByte();
			c.decoColor[i] = in.readInt();
			c.flags[i] = in.readByte();
			c.topBlock[i] = in.readShort();
			c.subBlock[i] = in.readShort();
			c.floorBlock[i] = in.readShort();
			c.biome[i] = in.readByte();
			int n = in.readUnsignedByte();
			c.column(i);
			for (int k = 0; k < n; k++) {
				int t = in.readShort(), b = in.readShort(), blk = in.readShort();
				Material m = Material.byId(in.readByte());
				int col = in.readInt();
				c.addRun(t, b, blk, m, col);
			}
		}
		if (in.readBoolean()) c.voxels = ChunkVoxels.read(in);
		c.compact();
		return c;
	}
}
