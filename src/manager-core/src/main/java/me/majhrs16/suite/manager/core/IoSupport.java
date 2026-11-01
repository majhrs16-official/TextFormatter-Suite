package me.majhrs16.suite.manager.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

/** Small filesystem primitives shared by the package manager transaction code. */
final class IoSupport {

    private IoSupport() {
    }

    static String operationId() {
        return Long.toHexString(System.currentTimeMillis()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 not available", failure);
        }
    }

    static String sha256(byte[] content) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 not available", failure);
        }
    }

    static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Some filesystems do not allow opening a directory as a channel.
            // The file itself was already fsynced before the rename.
        }
    }

    static void atomicWrite(Path target, byte[] content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            move(temp, target);
            fsyncDirectory(parent);
        } catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    static void move(Path source, Path target) throws IOException {
        Path targetParent = target.toAbsolutePath().getParent();
        Path sourceParent = source.toAbsolutePath().getParent();
        Files.createDirectories(targetParent);
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        fsyncDirectory(targetParent);
        if (!sourceParent.equals(targetParent)) {
            fsyncDirectory(sourceParent);
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static void deleteEmptyParents(Path start, Path stopAt) {
        Path current = start;
        Path stop = stopAt.toAbsolutePath().normalize();
        while (current != null) {
            current = current.toAbsolutePath().normalize();
            if (current.equals(stop) || !current.startsWith(stop)) {
                return;
            }
            if (!Files.isDirectory(current)) {
                return;
            }
            try (Stream<Path> entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            } catch (IOException failure) {
                return;
            }
            try {
                Files.deleteIfExists(current);
            } catch (IOException failure) {
                return;
            }
            current = current.getParent();
        }
    }
}
