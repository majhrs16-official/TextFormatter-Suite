package me.majhrs16.suite.manager.apt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Content-addressed download cache used by stage 1.
 *
 * <p>Only a fully downloaded, size-checked and SHA-256-verified file is
 * promoted from {@code .part} to the final path. A manifest records
 * {@code PENDING}/{@code COMPLETE} for each artifact of an operation so a
 * failed download can resume without repeating completed work.</p>
 */
public final class PackageCache {

    private final Path downloadsDir;
    private final Path operationsDir;
    private final OkHttpClient httpClient;
    private final PluginLogger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public PackageCache(Path managerDir, OkHttpClient httpClient, PluginLogger logger) {
        this.downloadsDir = managerDir.resolve("library/cache/downloads");
        this.operationsDir = managerDir.resolve("library/cache/operations");
        this.httpClient = httpClient;
        this.logger = logger;
    }

    public Path fetch(RepositoryManager.PackageCandidate candidate, String operationId)
            throws IOException {
        if (candidate.size() <= 0 || candidate.size() > AptInstaller.MAX_PACKAGE_BYTES) {
            throw new IOException("Invalid package size: " + candidate.size());
        }
        String sha256 = candidate.sha256();
        if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Package " + candidate.name() + " has no valid SHA-256");
        }
        sha256 = sha256.toLowerCase();
        String operation = operationId == null || operationId.isBlank()
                ? UUID.randomUUID().toString() : operationId;
        if (!operation.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("Invalid cache operation id");
        }
        String fileName = AptInstaller.packageFileName(candidate.name(), candidate.version());
        Path directory = downloadsDir.resolve(sha256);
        Path finalPath = directory.resolve(fileName);
        Path part = directory.resolve(fileName + ".part");
        Files.createDirectories(directory);
        Files.createDirectories(operationsDir);

        if (isComplete(finalPath, candidate)) {
            record(operation, candidate, "COMPLETE", finalPath);
            return finalPath;
        }
        Files.deleteIfExists(finalPath);

        long existing = Files.exists(part) ? Files.size(part) : 0L;
        // A complete .part is not trustworthy: it was never verified. Never
        // resume from it; only an incomplete prefix is a valid resume point.
        if (existing >= candidate.size()) {
            Files.deleteIfExists(part);
            existing = 0L;
        }
        record(operation, candidate, "PENDING", null);

        Request.Builder request = new Request.Builder().url(candidate.downloadUrl());
        if (existing > 0) {
            request.header("Range", "bytes=" + existing + "-");
        }
        try (Response response = httpClient.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + candidate.downloadUrl());
            }
            boolean append = existing > 0 && response.code() == 206;
            long already = append ? existing : 0L;
            if (!append && existing > 0) {
                Files.deleteIfExists(part);
            }
            long maxBytes = candidate.size() + 1024L * 1024L;
            try (var input = response.body().byteStream();
                 var output = Files.newOutputStream(part, StandardOpenOption.CREATE,
                         append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int read;
                long written = already;
                while ((read = input.read(buffer)) > 0) {
                    written += read;
                    if (written > maxBytes) {
                        throw new IOException("Download exceeds maximum allowed size: " + maxBytes);
                    }
                    output.write(buffer, 0, read);
                }
            }
        }

        if (Files.size(part) != candidate.size()) {
            throw new IOException("Size mismatch for downloaded package " + candidate.name()
                    + ": expected " + candidate.size() + ", got " + Files.size(part));
        }
        if (!sha256(part).equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException("Checksum mismatch for downloaded package " + candidate.name());
        }
        move(part, finalPath);
        record(operation, candidate, "COMPLETE", finalPath);
        logger.debug("Cached package " + candidate.name() + " at " + finalPath);
        return finalPath;
    }

    /**
     * Removes downloads, partial files and operation manifests. It never touches
     * installed packages, {@code library/status}, the journal or trash.
     */
    public void clean() throws IOException {
        deleteRecursively(downloadsDir);
        deleteRecursively(operationsDir);
        Files.createDirectories(downloadsDir);
        Files.createDirectories(operationsDir);
        logger.info("Package cache cleaned");
    }

    private boolean isComplete(Path file, RepositoryManager.PackageCandidate candidate)
            throws IOException {
        return Files.isRegularFile(file)
                && Files.size(file) == candidate.size()
                && sha256(file).equalsIgnoreCase(candidate.sha256());
    }

    private void record(String operationId, RepositoryManager.PackageCandidate candidate,
                        String status, Path path) throws IOException {
        Path manifestPath = operationsDir.resolve(operationId + ".json");
        Manifest manifest = Files.exists(manifestPath)
                ? gson.fromJson(Files.readString(manifestPath), Manifest.class)
                : new Manifest(operationId, Instant.now().toString(), new ArrayList<>());
        if (manifest == null) {
            manifest = new Manifest(operationId, Instant.now().toString(), new ArrayList<>());
        }
        manifest.artifacts.removeIf(artifact -> artifact.name.equals(candidate.name())
                && artifact.version.equals(candidate.version()));
        manifest.artifacts.add(new Artifact(candidate.name(), candidate.version(),
                candidate.sha256(), candidate.size(), status,
                path == null ? null : path.toString()));
        atomicWrite(manifestPath, gson.toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(Path file) throws IOException {
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

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            move(temp, target);
        } catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class Manifest {
        private final String operationId;
        private final String createdAt;
        private final List<Artifact> artifacts;

        private Manifest(String operationId, String createdAt, List<Artifact> artifacts) {
            this.operationId = operationId;
            this.createdAt = createdAt;
            this.artifacts = artifacts;
        }
    }

    private static final class Artifact {
        private final String name;
        private final String version;
        private final String sha256;
        private final long size;
        private final String status;
        private final String path;

        private Artifact(String name, String version, String sha256, long size,
                         String status, String path) {
            this.name = name;
            this.version = version;
            this.sha256 = sha256;
            this.size = size;
            this.status = status;
            this.path = path;
        }
    }
}
