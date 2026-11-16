package dev.maqueta.core;

import dev.maqueta.core.mesh.MeshData;
import dev.maqueta.core.mesh.TerrainMesher;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelTintTest {
	@Test
	void tintedGrassTopIsGreen() {
		ColumnStore store = new ColumnStore();
		ChunkColumns c = new ChunkColumns(0, 0);
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++)
				c.set(x, z, 64, 64, Material.GRASS, 0x7FB238, Material.DIRT, 0x866043, 0x866043, 0, 0);
		ChunkVoxels v = new ChunkVoxels(60, 4);
		int grass = v.entryIndex(new ChunkVoxels.Entry("minecraft:grass_block", Material.GRASS, 0x959595, ChunkVoxels.TINT_GRASS, 0));
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++) {
				for (int y = 60; y < 64; y++) v.setOpaque(x, y, z);
			}
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++) v.addShell(x, 63, z, grass);
		java.util.Arrays.fill(v.grassTint, 0x79C05A);
		c.voxels = v;
		store.put(c);
		MeshData mesh = new TerrainMesher(store, 40).build(0, 0, 0, 0, 0);
		ByteBuffer b = mesh.vertices().duplicate().order(ByteOrder.nativeOrder());
		int r = b.get(12) & 255, g = b.get(13) & 255, bl = b.get(14) & 255;
		assertTrue(g > r + 20 && g > bl + 20, "grass top should be green, got " + r + "," + g + "," + bl);
	}
}
