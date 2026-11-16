package dev.maqueta.core.cave;

import dev.maqueta.core.Material;

/**
 * A dense block volume around the player used for the underground layer. Material
 * {@link Material#NONE} means passable (air, plants, torches). Emission is the light
 * a block itself gives off, 0..15.
 */
public final class VoxelVolume {
	public final int originX, originY, originZ;
	public final int sizeX, sizeY, sizeZ;
	final byte[] material;
	final int[] color;
	final byte[] emission;
	final short[] tileTop;
	final short[] tileSide;

	public VoxelVolume(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int n = sizeX * sizeY * sizeZ;
		material = new byte[n];
		color = new int[n];
		emission = new byte[n];
		tileTop = new short[n];
		tileSide = new short[n];
	}

	public int index(int lx, int ly, int lz) {
		return (ly * sizeZ + lz) * sizeX + lx;
	}

	public boolean inBounds(int lx, int ly, int lz) {
		return lx >= 0 && ly >= 0 && lz >= 0 && lx < sizeX && ly < sizeY && lz < sizeZ;
	}

	public void set(int lx, int ly, int lz, Material mat, int rgb, int emit) {
		int i = index(lx, ly, lz);
		material[i] = (byte) mat.ordinal();
		color[i] = rgb;
		emission[i] = (byte) emit;
	}

	/** Sets a block with its texture tiles (top face, side faces). */
	public void set(int lx, int ly, int lz, Material mat, int rgb, int emit, int top, int side) {
		set(lx, ly, lz, mat, rgb, emit);
		int i = index(lx, ly, lz);
		tileTop[i] = (short) top;
		tileSide[i] = (short) side;
	}

	public Material material(int lx, int ly, int lz) {
		return Material.byId(material[index(lx, ly, lz)]);
	}

	public boolean solid(int lx, int ly, int lz) {
		if (!inBounds(lx, ly, lz)) return false;
		return material[index(lx, ly, lz)] != 0;
	}
}
