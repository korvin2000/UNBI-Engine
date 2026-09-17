package com.unbi.engine.llm.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Private files are published only by durable same-directory atomic replacement. */
public final class SecretFiles {
    private SecretFiles() {}

    public static void directory(Path directory) throws IOException {
        rejectSymlinks(directory);
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("Credential directories require owner-only permissions (0700)");
    }

    public static void write(Path file, byte[] bytes) throws IOException {
        file = file.toAbsolutePath().normalize();
        rejectSymlinks(file);
        var parent = file.getParent();
        Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var temporary = Files.createTempFile(parent, ".credential-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            rejectSymlinks(file);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(parent, StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static byte[] read(Path file) throws IOException {
        rejectSymlinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))
            throw new IOException("Credential files require owner-only permissions (0600)");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Credential file exceeds its size limit");
            return bytes;
        }
    }

    public static void rejectSymlinks(Path path) throws IOException {
        for (var current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Credential paths must not contain symbolic links");
        }
    }
}
