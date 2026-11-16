package dev.maqueta.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The visible band of a chunk, stored the way Minecraft itself thinks about blocks: a 3D grid.
 * <ul>
 *   <li>{@link #opaque}: one bit per block in {@code [yMin, yMin + height)}. A face is drawn only
 *       where an opaque block touches a non-opaque one, exactly like the game's face culling.
 *       Nothing is guessed, so floating blocks, branches, bridges, arches, towers and overhangs
 *       are what they are.</li>
 *   <li>{@link #water}: one bit per water block.</li>
 *   <li>Block identity only for the cells that can show a face (the shell), as palette indices.</li>
 * </ul>
 * Below {@code yMin} counts as solid rock, above the band counts as air.
 */
public final class ChunkVoxels {
	public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3, TINT_FIXED = 4;

	/** A block kind in this chunk: key (id or id[state] for shaped blocks), material, colour, tint. */
	public record Entry(String key, Material material, int baseColor, int tint, int fixedTint) {
	}

	public final int yMin;
	public final int height;
	private final long[] opaque;
	private final long[] water;
	public final List<Entry> palette = new ArrayList<>();
	private int count;
	private int[] cells = new int[256];
	private short[] blocks = new short[256];
	/** Per column biome colours: grass, foliage, water. */
	public final int[] grassTint = new int[256];
	public final int[] foliageTint = new int[256];
	public final int[] waterTint = new int[256];

	public ChunkVoxels(int yMin, int height) {
		this.yMin = yMin;
		this.height = Math.max(1, height);
		int words = (this.height * 256 + 63) >>> 6;
		opaque = new long[words];
		water = new long[words];
	}

	public static int cell(int lx, int ly, int lz) {
		return (ly << 8) | (lz << 4) | lx;
	}

	public int yMax() {
		return yMin + height;
	}

	// ---------------------------------------------------------------- occupancy

	public void setOpaque(int lx, int y, int lz) {
		int c = cell(lx, y - yMin, lz);
		opaque[c >>> 6] |= 1L << (c & 63);
	}

	public void setWater(int lx, int y, int lz) {
		int c = cell(lx, y - yMin, lz);
		water[c >>> 6] |= 1L << (c & 63);
	}

	/** Opaque at world y? Below the band is rock, above it is air. */
	public boolean opaque(int lx, int y, int lz) {
		if (y < yMin) return true;
		if (y >= yMin + height) return false;
		int c = cell(lx, y - yMin, lz);
		return (opaque[c >>> 6] & (1L << (c & 63))) != 0;
	}

	public boolean water(int lx, int y, int lz) {
		if (y < yMin || y >= yMin + height) return false;
		int c = cell(lx, y - yMin, lz);
		return (water[c >>> 6] & (1L << (c & 63))) != 0;
	}

	// ---------------------------------------------------------------- shell

	public int entryIndex(Entry e) {
		int k = palette.indexOf(e);
		if (k >= 0) return k;
		palette.add(e);
		return palette.size() - 1;
	}

	/** Records the identity of a shell block. Cells must be added in increasing cell order. */
	public void addShell(int lx, int y, int lz, int entry) {
		if (count == cells.length) {
			int capacity = Math.max(16, count * 2);
			cells = Arrays.copyOf(cells, capacity);
			blocks = Arrays.copyOf(blocks, capacity);
		}
		cells[count] = cell(lx, y - yMin, lz);
		blocks[count] = (short) entry;
		count++;
	}

	void compactShell() {
		if (count > cells.length * 3 / 4 || count == cells.length) return;
		cells = Arrays.copyOf(cells, count);
		blocks = Arrays.copyOf(blocks, count);
	}

	public int shellCount() {
		return count;
	}

	public int shellX(int k) {
		return cells[k] & 15;
	}

	public int shellZ(int k) {
		return (cells[k] >> 4) & 15;
	}

	public int shellY(int k) {
		return (cells[k] >> 8) + yMin;
	}

	public Entry shellEntry(int k) {
		return palette.get(blocks[k]);
	}

	/** Identity of the block at a cell, or null if it is not part of the shell. */
	public Entry entryAt(int lx, int y, int lz) {
		if (y < yMin || y >= yMin + height) return null;
		int key = cell(lx, y - yMin, lz);
		int k = Arrays.binarySearch(cells, 0, count, key);
		return k >= 0 ? palette.get(blocks[k]) : null;
	}

	/** Source colour of a shell block: its texture colour, or the biome tint times its brightness. */
	public int sourceColor(Entry e, int lx, int lz) {
		int i = (lz << 4) | lx;
		int tint = switch (e.tint()) {
			case TINT_GRASS -> grassTint[i];
			case TINT_FOLIAGE -> foliageTint[i];
			case TINT_WATER -> waterTint[i];
			case TINT_FIXED -> e.fixedTint();
			default -> -1;
		};
		if (tint == -1) return e.baseColor();
		tint &= 0xFFFFFF; // biome colours may arrive as ARGB
		int b = e.baseColor();
		float lum = (((b >> 16) & 0xFF) * 0.299f + ((b >> 8) & 0xFF) * 0.587f + (b & 0xFF) * 0.114f) / 255f;
		lum = Math.min(1f, lum * 1.08f);
		if (b == 0) lum = 1f;
		return (Math.round(((tint >> 16) & 0xFF) * lum) << 16) | (Math.round(((tint >> 8) & 0xFF) * lum) << 8) | Math.round((tint & 0xFF) * lum);
	}

	// ---------------------------------------------------------------- persistence

	public void write(DataOutput out) throws IOException {
		out.writeShort(yMin);
		out.writeShort(height);
		out.writeShort(palette.size());
		for (Entry e : palette) {
			out.writeUTF(e.key());
			out.writeByte(e.material().ordinal());
			out.writeInt(e.baseColor());
			out.writeByte(e.tint());
			out.writeInt(e.fixedTint());
		}
		for (long w : opaque) out.writeLong(w);
		for (long w : water) out.writeLong(w);
		out.writeInt(count);
		for (int k = 0; k < count; k++) {
			out.writeInt(cells[k]);
			out.writeShort(blocks[k]);
		}
		for (int i = 0; i < 256; i++) {
			out.writeInt(grassTint[i]);
			out.writeInt(foliageTint[i]);
			out.writeInt(waterTint[i]);
		}
	}

	public static ChunkVoxels read(DataInput in) throws IOException {
		ChunkVoxels v = new ChunkVoxels(in.readShort(), in.readShort());
		int n = in.readUnsignedShort();
		for (int k = 0; k < n; k++) {
			v.palette.add(new Entry(in.readUTF(), Material.byId(in.readByte()), in.readInt(), in.readByte(), in.readInt()));
		}
		for (int k = 0; k < v.opaque.length; k++) v.opaque[k] = in.readLong();
		for (int k = 0; k < v.water.length; k++) v.water[k] = in.readLong();
		int c = in.readInt();
		v.cells = new int[Math.max(16, c)];
		v.blocks = new short[Math.max(16, c)];
		for (int k = 0; k < c; k++) {
			v.cells[k] = in.readInt();
			v.blocks[k] = in.readShort();
		}
		v.count = c;
		for (int i = 0; i < 256; i++) {
			v.grassTint[i] = in.readInt();
			v.foliageTint[i] = in.readInt();
			v.waterTint[i] = in.readInt();
		}
		return v;
	}
}
