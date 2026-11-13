package dev.maqueta.core.io;

import dev.maqueta.core.ChunkColumns;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Explored terrain on disk: one file per 32x32-chunk region (512 blocks, like the game's
 * own regions). Header carries the format version so old files can be migrated or skipped.
 * Only source data is stored; meshes and light are always regenerated.
 */
public final class RegionStorage {
	private static final int MAGIC = 0x4D51544B; // "MQTK"
	public static final int REGION_SHIFT = 5;

	private final Path dir;

	public RegionStorage(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	public static long regionKey(int chunkX, int chunkZ) {
		return ChunkColumns.key(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
	}

	private Path file(int rx, int rz) {
		return dir.resolve("r." + rx + "." + rz + ".mqt");
	}

	public void write(int rx, int rz, List<ChunkColumns> chunks) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
		try (DataOutputStream head = new DataOutputStream(bytes)) {
			head.writeInt(MAGIC);
			head.writeInt(ChunkColumns.FORMAT);
			head.writeInt(chunks.size());
			head.flush();
			DeflaterOutputStream z = new DeflaterOutputStream(bytes);
			DataOutputStream out = new DataOutputStream(z);
			for (ChunkColumns c : chunks) c.write(out);
			out.flush();
			z.finish();
		}
		AtomicFiles.write(file(rx, rz), bytes.toByteArray());
	}

	public List<ChunkColumns> read(int rx, int rz) throws IOException {
		Path f = file(rx, rz);
		List<ChunkColumns> out = new ArrayList<>();
		if (!Files.exists(f)) return out;
		byte[] data = Files.readAllBytes(f);
		try (DataInputStream head = new DataInputStream(new ByteArrayInputStream(data))) {
			if (head.readInt() != MAGIC) return out;
			int format = head.readInt();
			if (format != ChunkColumns.FORMAT) return out; // older format: re-explore that region
			int n = head.readInt();
			DataInputStream in = new DataInputStream(new InflaterInputStream(head));
			for (int i = 0; i < n; i++) out.add(ChunkColumns.read(in));
		}
		return out;
	}

	/** Region coordinates of every saved file. */
	public List<int[]> listRegions() {
		List<int[]> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (var stream = Files.list(dir)) {
			stream.forEach(p -> {
				String n = p.getFileName().toString();
				if (!n.startsWith("r.") || !n.endsWith(".mqt")) return;
				String[] parts = n.split("\\.");
				if (parts.length != 4) return;
				try {
					out.add(new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
				} catch (NumberFormatException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
		return out;
	}
}
