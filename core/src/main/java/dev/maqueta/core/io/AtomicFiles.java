package dev.maqueta.core.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Write to a temp file then rename, so a crash never leaves a half-written map file. */
public final class AtomicFiles {
	private AtomicFiles() {
	}

	public static void write(Path target, byte[] data) throws IOException {
		Path dir = target.getParent();
		if (dir != null) Files.createDirectories(dir);
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(tmp, data);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
