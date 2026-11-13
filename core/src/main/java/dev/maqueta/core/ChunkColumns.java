package dev.maqueta.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Source data for one 16x16 chunk column: what the player actually saw, not how it looks.
 * Changing the style only re-runs {@link Palette} and the meshers on this data.
 */
public final class ChunkColumns {
	public static final int SIZE = 16;
	public static final int AREA = SIZE * SIZE;
	public static final short UNKNOWN = Short.MIN_VALUE;

	public final int chunkX;
	public final int chunkZ;
	/** Top of the visible surface (block y + 1). For water this is the water surface. */
	public final short[] top = new short[AREA];
	/** Top of the solid floor. Equal to {@link #top} except under fluids. */
	public final short[] floor = new short[AREA];
	public final byte[] material = new byte[AREA];
	public final byte[] subMaterial = new byte[AREA];
	public final int[] color = new int[AREA];
	public final int[] subColor = new int[AREA];
	/** Floor colour under fluids (seabed). */
	public final int[] floorColor = new int[AREA];
	public final byte[] emission = new byte[AREA];
	/** Small decoration on top (flowers, grass tufts, torches); 0 = none. Painted, not modelled. */
	public final int[] decoColor = new int[AREA];
	public long capturedAt;

	public ChunkColumns(int chunkX, int chunkZ) {
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		java.util.Arrays.fill(top, UNKNOWN);
		java.util.Arrays.fill(floor, UNKNOWN);
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

	public void set(int lx, int lz, int topY, int floorY, Material mat, int rgb, Material sub, int subRgb,
					int floorRgb, int emit, int deco) {
		int i = index(lx, lz);
		top[i] = (short) topY;
		floor[i] = (short) floorY;
		material[i] = (byte) mat.ordinal();
		subMaterial[i] = (byte) sub.ordinal();
		color[i] = rgb & 0xFFFFFF;
		subColor[i] = subRgb & 0xFFFFFF;
		floorColor[i] = floorRgb & 0xFFFFFF;
		emission[i] = (byte) emit;
		decoColor[i] = deco;
	}

	public Material material(int i) {
		return Material.byId(material[i]);
	}

	public Material subMaterial(int i) {
		return Material.byId(subMaterial[i]);
	}

	public boolean isKnown(int i) {
		return top[i] != UNKNOWN;
	}

	/** Serialised form version. Bump when the layout changes. */
	public static final int FORMAT = 2;

	public void write(DataOutput out) throws IOException {
		out.writeInt(chunkX);
		out.writeInt(chunkZ);
		out.writeLong(capturedAt);
		for (int i = 0; i < AREA; i++) {
			out.writeShort(top[i]);
			out.writeShort(floor[i]);
			out.writeByte(material[i]);
			out.writeByte(subMaterial[i]);
			out.writeInt(color[i]);
			out.writeInt(subColor[i]);
			out.writeInt(floorColor[i]);
			out.writeByte(emission[i]);
			out.writeInt(decoColor[i]);
		}
	}

	public static ChunkColumns read(DataInput in) throws IOException {
		ChunkColumns c = new ChunkColumns(in.readInt(), in.readInt());
		c.capturedAt = in.readLong();
		for (int i = 0; i < AREA; i++) {
			c.top[i] = in.readShort();
			c.floor[i] = in.readShort();
			c.material[i] = in.readByte();
			c.subMaterial[i] = in.readByte();
			c.color[i] = in.readInt();
			c.subColor[i] = in.readInt();
			c.floorColor[i] = in.readInt();
			c.emission[i] = in.readByte();
			c.decoColor[i] = in.readInt();
		}
		return c;
	}
}
