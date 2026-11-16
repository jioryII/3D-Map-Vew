package dev.maqueta.core.cave;

import dev.maqueta.core.Material;
import dev.maqueta.core.Palette;
import dev.maqueta.core.mesh.MeshBuilder;
import dev.maqueta.core.mesh.MeshData;

import java.util.ArrayDeque;

/**
 * Meshes the space the player is actually in. Air connected to the player is flood-filled;
 * only block faces that touch that air are emitted, so you see the inside of your cave
 * and not every other tunnel in the mountain (fair play by construction). Everything
 * above the cut height is removed so the cave reads from above like a sliced model.
 */
public final class CaveMesher {
	private static final int[][] FACES = {
		// dx, dy, dz, normal
		{0, 1, 0, MeshBuilder.N_UP}, {0, -1, 0, MeshBuilder.N_DOWN},
		{0, 0, -1, MeshBuilder.N_NORTH}, {0, 0, 1, MeshBuilder.N_SOUTH},
		{-1, 0, 0, MeshBuilder.N_WEST}, {1, 0, 0, MeshBuilder.N_EAST},
	};

	/** Result: the mesh plus how much air was reached (for the auto-zoom heuristic). */
	public record Result(MeshData mesh, int reachedCells, int cutY) {
	}

	private CaveMesher() {
	}

	public static Result build(VoxelVolume v, int playerX, int playerY, int playerZ, int cutY, int maxCells, int anchorX, int anchorZ) {
		return build(v, playerX, playerY, playerZ, cutY, maxCells, anchorX, anchorZ, false);
	}

	/**
	 * @param showSurroundings also draw the other caves and the rock section, shaded (like a
	 *                         geological cut). Off in fair-play mode: then only the connected
	 *                         space is meshed.
	 */
	public static Result build(VoxelVolume v, int playerX, int playerY, int playerZ, int cutY, int maxCells, int anchorX, int anchorZ,
							   boolean showSurroundings) {
		byte[] reached = floodFill(v, playerX - v.originX, playerY - v.originY, playerZ - v.originZ, cutY - v.originY, maxCells);
		int count = 0;
		for (byte b : reached) count += b;
		byte[] light = propagateLight(v, reached);

		MeshBuilder mb = new MeshBuilder(4096);
		int localCut = cutY - v.originY;
		for (int ly = 0; ly < v.sizeY && ly <= localCut; ly++) {
			for (int lz = 0; lz < v.sizeZ; lz++) {
				for (int lx = 0; lx < v.sizeX; lx++) {
					int i = v.index(lx, ly, lz);
					if (v.material[i] == 0) continue;
					Material mat = Material.byId(v.material[i]);
					int wx = lx + v.originX, wz = lz + v.originZ;
					int rgb = Palette.stylize(mat, v.color[i], Palette.variation(wx * 31 + ly, wz) * 0.5f);
					for (int[] f : FACES) {
						int nx = lx + f[0], ny = ly + f[1], nz = lz + f[2];
						int flag;
						if (f[3] == MeshBuilder.N_UP && ly == localCut) {
							// Cut plane: the rock section. Near the player it is part of the scene,
							// further away it is only drawn as the shaded geological cut.
							boolean near = touchesReached(v, reached, lx, ly, lz);
							if (!near && !showSurroundings) continue;
							flag = MeshBuilder.FLAG_SECTION | (near ? 0 : MeshBuilder.FLAG_UNSEEN);
						} else {
							if (!v.inBounds(nx, ny, nz)) continue;
							int ni = v.index(nx, ny, nz);
							if (v.material[ni] != 0) continue;
							if (reached[ni] != 0) flag = 0;
							else if (showSurroundings) flag = MeshBuilder.FLAG_UNSEEN;
							else continue;
						}
						int ni = v.inBounds(nx, ny, nz) ? v.index(nx, ny, nz) : i;
						float emit = (light[ni] & 0xFF) / 15f;
						boolean sliced = (flag & MeshBuilder.FLAG_SECTION) != 0;
						int faceRgb = sliced ? Palette.shade(rgb, -0.14f) : rgb;
						int tile = f[3] == MeshBuilder.N_UP || f[3] == MeshBuilder.N_DOWN ? v.tileTop[i] : v.tileSide[i];
						mb.texture(tile, mat.ordinal());
						emitFace(mb, v, lx, ly, lz, f, faceRgb, emit, anchorX, anchorZ, sliced, flag);
					}
				}
			}
		}
		return new Result(MeshData.of(mb), count, cutY);
	}

	private static boolean touchesReached(VoxelVolume v, byte[] reached, int lx, int ly, int lz) {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 0; dy++) {
					int x = lx + dx, y = ly + dy, z = lz + dz;
					if (v.inBounds(x, y, z) && reached[v.index(x, y, z)] != 0) return true;
				}
			}
		}
		return false;
	}

	static byte[] floodFill(VoxelVolume v, int sx, int sy, int sz, int localCut, int maxCells) {
		byte[] reached = new byte[v.sizeX * v.sizeY * v.sizeZ];
		if (!v.inBounds(sx, sy, sz)) return reached;
		// The player's own cell may be partially solid (slabs, path); start at head height if needed.
		if (v.material[v.index(sx, sy, sz)] != 0 && v.inBounds(sx, sy + 1, sz)) sy++;
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[]{sx, sy, sz});
		reached[v.index(sx, sy, sz)] = 1;
		int n = 0;
		while (!queue.isEmpty() && n < maxCells) {
			int[] p = queue.poll();
			n++;
			for (int[] f : FACES) {
				int x = p[0] + f[0], y = p[1] + f[1], z = p[2] + f[2];
				if (!v.inBounds(x, y, z) || y > localCut + 1) continue;
				int i = v.index(x, y, z);
				if (reached[i] != 0 || v.material[i] != 0) continue;
				reached[i] = 1;
				queue.add(new int[]{x, y, z});
			}
		}
		return reached;
	}

	/** Minecraft-style block light flood: emitters spread, losing one level per step through air. */
	static byte[] propagateLight(VoxelVolume v, byte[] reached) {
		int total = v.sizeX * v.sizeY * v.sizeZ;
		byte[] light = new byte[total];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int i = 0; i < total; i++) {
			if (v.emission[i] > 0) {
				light[i] = v.emission[i];
				queue.add(i);
			}
		}
		int sxy = v.sizeX;
		int sxz = v.sizeX * v.sizeZ;
		while (!queue.isEmpty()) {
			int i = queue.poll();
			int level = light[i];
			if (level <= 1) continue;
			int lx = i % v.sizeX;
			int lz = (i / v.sizeX) % v.sizeZ;
			int ly = i / sxz;
			int[][] nb = {{lx + 1, ly, lz}, {lx - 1, ly, lz}, {lx, ly + 1, lz}, {lx, ly - 1, lz}, {lx, ly, lz + 1}, {lx, ly, lz - 1}};
			for (int[] p : nb) {
				if (!v.inBounds(p[0], p[1], p[2])) continue;
				int j = v.index(p[0], p[1], p[2]);
				if (v.material[j] != 0 && v.emission[j] == 0) continue;
				if (light[j] >= level - 1) continue;
				light[j] = (byte) (level - 1);
				queue.add(j);
			}
		}
		return light;
	}

	private static void emitFace(MeshBuilder mb, VoxelVolume v, int lx, int ly, int lz, int[] f, int rgb, float emit,
								 int anchorX, int anchorZ, boolean sliced, int extraFlags) {
		float x = lx + v.originX - anchorX, y = ly + v.originY, z = lz + v.originZ - anchorZ;
		float[][] quad = faceQuad(f[3]);
		int code = f[3] | MeshBuilder.FLAG_INSIDE | extraFlags;
		float[] ao = new float[4];
		for (int k = 0; k < 4; k++) {
			ao[k] = sliced ? 0.9f : cornerAo(v, lx, ly, lz, f, quad[k]);
		}
		int start = ao[0] + ao[2] < ao[1] + ao[3] ? 1 : 0;
		for (int q = 0; q < 4; q++) {
			int k = (q + start) & 3;
			float[] c = quad[k];
			mb.vertex(x + c[0], y + c[1], z + c[2], rgb, code, 0f, 0.35f, ao[k], emit);
		}
	}

	/** Corner offsets (0/1) for each face, counter-clockwise seen from outside. */
	private static float[][] faceQuad(int normal) {
		return switch (normal) {
			case MeshBuilder.N_UP -> new float[][]{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
			case MeshBuilder.N_DOWN -> new float[][]{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}};
			case MeshBuilder.N_NORTH -> new float[][]{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}};
			case MeshBuilder.N_SOUTH -> new float[][]{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}};
			case MeshBuilder.N_WEST -> new float[][]{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}};
			default -> new float[][]{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}};
		};
	}

	private static float cornerAo(VoxelVolume v, int lx, int ly, int lz, int[] f, float[] corner) {
		// Neighbour cell in front of the face, then step towards the corner along the two tangents.
		int fx = lx + f[0], fy = ly + f[1], fz = lz + f[2];
		int[] t = new int[3];
		for (int a = 0; a < 3; a++) {
			int fa = a == 0 ? f[0] : (a == 1 ? f[1] : f[2]);
			t[a] = fa != 0 ? 0 : (corner[a] > 0.5f ? 1 : -1);
		}
		int[] axes = new int[2];
		int k = 0;
		for (int a = 0; a < 3 && k < 2; a++) if (t[a] != 0) axes[k++] = a;
		int[] s1 = {fx, fy, fz}, s2 = {fx, fy, fz}, c = {fx, fy, fz};
		s1[axes[0]] += t[axes[0]];
		s2[axes[1]] += t[axes[1]];
		c[axes[0]] += t[axes[0]];
		c[axes[1]] += t[axes[1]];
		return dev.maqueta.core.light.LightBaker.vertexAo(v.solid(s1[0], s1[1], s1[2]), v.solid(s2[0], s2[1], s2[2]), v.solid(c[0], c[1], c[2]));
	}
}
