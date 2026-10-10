package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Package installer (dpkg equivalent) - handles installation from local ZIP artifacts.
 * Validates metadata, inventory, dependencies, and extracts files safely.
 */
public final class PackageInstaller {

    private final LocalPackageDatabase database;
    private final Path installRoot;
    private final PluginLogger logger;

    public PackageInstaller(LocalPackageDatabase database, Path installRoot, PluginLogger logger) {
        this.database = database;
        this.installRoot = installRoot.toAbsolutePath();
        this.logger = logger;
    }

    /**
     * Installs a package from a local distribution ZIP.
     * Validates manifest, dependencies, checksums, and extracts files.
     *
     * @param zipPath path to the distribution ZIP
     * @param force if true, overwrite existing files (after verification)
     * @return list of installed package entries (including metapackage contents)
     * @throws InstallException if installation fails
     */
    public List<PackageEntry> install(Path zipPath, boolean force) throws InstallException {
        logger.info("Installing package from: " + zipPath);
        
        // 1. Validate ZIP and read manifest
        PackageManifest manifest;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            manifest = readManifest(zip);
        } catch (IOException e) {
            throw new InstallException("Failed to read ZIP: " + e.getMessage(), e);
        }

        // 2. Verify ZIP checksum against manifest
        String zipSha256;
        try {
            zipSha256 = computeSha256(zipPath);
        } catch (IOException e) {
            throw new InstallException("Failed to compute ZIP SHA256: " + e.getMessage(), e);
        }
        if (manifest.mainPackage().sha256() != null && !manifest.mainPackage().sha256().isBlank()) {
            if (!zipSha256.equalsIgnoreCase(manifest.mainPackage().sha256())) {
                throw new InstallException("ZIP SHA256 mismatch: expected " + manifest.mainPackage().sha256() + ", got " + zipSha256);
            }
        }

        // 3. Verify size
        long zipSize;
        try {
            zipSize = Files.size(zipPath);
        } catch (IOException e) {
            throw new InstallException("Failed to get ZIP size: " + e.getMessage(), e);
        }
        if (manifest.mainPackage().size() > 0 && zipSize != manifest.mainPackage().size()) {
            throw new InstallException("ZIP size mismatch: expected " + manifest.mainPackage().size() + ", got " + zipSize);
        }

        // 4. Check dependencies
        checkDependencies(manifest);

        // 5. Check for conflicts (file ownership)
        checkFileConflicts(manifest, force);

        // 6. Extract files and build package entries
        List<PackageEntry> installedEntries = new ArrayList<>();
        try {
            for (PackageEntry pkgEntry : manifest.allPackages()) {
                // Extract files for this package
                Map<String, FileEntry> extractedFiles = extractPackageFiles(zipPath, pkgEntry);
                
                // Create entry with actual file info
                PackageEntry installedEntry = new PackageEntry(
                        pkgEntry.name(),
                        pkgEntry.version(),
                        pkgEntry.channel(),
                        pkgEntry.type(),
                        pkgEntry.dependencies(),
                        pkgEntry.arch(),
                        pkgEntry.platform(),
                        pkgEntry.url(),
                        pkgEntry.os(),
                        zipSha256, // Use ZIP SHA256 as artifact identifier
                        zipSize,
                        extractedFiles,
                        zipSha256, // Source artifact
                        pkgEntry.parentPackage(),
                        Map.of(
                                "Installed-Time", Instant.now().toString(),
                                "Status", "installed"
                        )
                );
                
                // Register in database
                database.registerPackage(installedEntry);
                installedEntries.add(installedEntry);
                logger.info("Installed package: " + installedEntry.name() + " " + installedEntry.version() + " (" + installedEntry.channel() + ")");
            }
        } catch (IOException e) {
            // Rollback on failure
            for (PackageEntry entry : installedEntries) {
                try {
                    database.unregisterPackage(entry.name(), entry.version(), entry.channel());
                } catch (IOException ignored) {}
            }
            throw new InstallException("Failed to extract/install package: " + e.getMessage(), e);
        }

        return installedEntries;
    }

    /**
     * Removes a package.
     *
     * @param name package name
     * @param version package version (or "any" for latest)
     * @param channel package channel
     * @param autoRemoveDependencies if true, also remove unused dependencies
     * @throws InstallException if removal fails
     */
    public void remove(String name, String version, String channel, boolean autoRemoveDependencies) throws InstallException {
        logger.info("Removing package: " + name + " " + version + " (" + channel + ")");

        // Find package to remove
        PackageEntry entry;
        if ("any".equals(version)) {
            Optional<PackageEntry> latest = database.getLatestInstalled(name, channel);
            if (latest.isEmpty()) {
                throw new InstallException("Package not installed: " + name + " (" + channel + ")");
            }
            entry = latest.get();
        } else {
            Optional<PackageEntry> pkg = database.getPackage(name, version, channel);
            if (pkg.isEmpty()) {
                throw new InstallException("Package not installed: " + name + " " + version + " (" + channel + ")");
            }
            entry = pkg.get();
        }

        // Check reverse dependencies
        List<PackageEntry> reverseDeps = database.getReverseDependencies(name);
        if (!reverseDeps.isEmpty()) {
            String deps = reverseDeps.stream()
                    .map(p -> p.name() + " " + p.version())
                    .collect(Collectors.joining(", "));
            throw new InstallException("Cannot remove " + name + ": required by " + deps);
        }

        // Check for shared files
        Map<String, Set<String>> orphanedFiles = database.checkOrphanedFiles(entry);
        Map<String, Set<String>> sharedFiles = new HashMap<>();
        for (FileEntry file : entry.files().values()) {
            Set<String> owners = database.getFileOwners(file.path());
            if (owners.size() > 1) {
                sharedFiles.put(file.path(), owners);
            }
        }

        if (!sharedFiles.isEmpty()) {
            logger.warn("Package " + name + " shares files with other packages: " + sharedFiles.keySet());
            // Don't remove shared files, just unregister
        }

        // Remove files that are exclusively owned by this package
        for (Map.Entry<String, Set<String>> orphaned : orphanedFiles.entrySet()) {
            Path filePath = installRoot.resolve(orphaned.getKey());
            try {
                Files.deleteIfExists(filePath);
                logger.debug("Removed file: " + orphaned.getKey());
            } catch (IOException e) {
                logger.warn("Failed to remove file " + orphaned.getKey() + ": " + e.getMessage());
            }
        }

        // Unregister from database
        try {
            database.unregisterPackage(entry.name(), entry.version(), entry.channel());
        } catch (IOException e) {
            throw new InstallException("Failed to unregister package: " + e.getMessage(), e);
        }

        // Auto-remove orphaned dependencies
        if (autoRemoveDependencies) {
            removeOrphanedDependencies();
        }
    }

    /**
     * Removes dependencies that are no longer required by any installed package.
     */
    private void removeOrphanedDependencies() {
        List<PackageEntry> deps = database.getPackagesByType(PackageType.DEPENDENCY);
        for (PackageEntry dep : deps) {
            List<PackageEntry> reverseDeps = database.getReverseDependencies(dep.name());
            if (reverseDeps.isEmpty()) {
                logger.info("Removing orphaned dependency: " + dep.name());
                try {
                    remove(dep.name(), dep.version(), dep.channel(), false);
                } catch (InstallException e) {
                    logger.warn("Failed to remove orphaned dependency " + dep.name() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Verifies an installed package.
     */
    public LocalPackageDatabase.VerificationResult verify(String name, String version, String channel) {
        Optional<PackageEntry> entry = database.getPackage(name, version, channel);
        if (entry.isEmpty()) {
            return new LocalPackageDatabase.VerificationResult(name, false, 
                    List.of(new LocalPackageDatabase.VerificationIssue("", "NOT_INSTALLED", "Package not found")));
        }
        return database.verifyPackage(entry.get(), installRoot);
    }

    /**
     * Lists files belonging to a package.
     */
    public List<FileEntry> listFiles(String name, String version, String channel) {
        Optional<PackageEntry> entry = database.getPackage(name, version, channel);
        return entry.map(e -> new ArrayList<>(e.files().values())).orElseGet(ArrayList::new);
    }

    private PackageManifest readManifest(ZipFile zip) throws IOException, InstallException {
        ZipEntry manifestEntry = zip.getEntry("METADATA/package.list");
        if (manifestEntry == null) {
            throw new InstallException("Missing METADATA/package.list in ZIP");
        }

        try (var is = zip.getInputStream(manifestEntry)) {
            String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            List<PackageEntry> entries;
            try {
                entries = ManifestParser.parseManifest(content);
            } catch (ManifestParser.ParseException e) {
                throw new InstallException("Invalid manifest: " + e.getMessage(), e);
            }

            if (entries.isEmpty()) {
                throw new InstallException("Manifest contains no packages");
            }

            // First entry is the main package
            PackageEntry mainPackage = entries.get(0);
            List<PackageEntry> additionalPackages = entries.size() > 1 ? entries.subList(1, entries.size()) : List.of();

            // Validate: check for duplicate names
            Set<String> seen = new HashSet<>();
            for (PackageEntry e : entries) {
                if (!seen.add(e.name())) {
                    throw new InstallException("Duplicate package name in manifest: " + e.name());
                }
            }

            return new PackageManifest(mainPackage, additionalPackages, entries);
        }
    }

    private void checkDependencies(PackageManifest manifest) throws InstallException {
        for (PackageEntry pkg : manifest.allPackages()) {
            for (String dep : pkg.dependencies()) {
                // Check if dependency is satisfied by installed packages
                boolean satisfied = false;
                for (PackageEntry installed : database.getAllPackages()) {
                    if (installed.name().equals(dep)) {
                        // TODO: Version constraint checking
                        satisfied = true;
                        break;
                    }
                }
                if (!satisfied) {
                    throw new InstallException("Unsatisfied dependency: " + pkg.name() + " requires " + dep);
                }
            }
        }
    }

    private void checkFileConflicts(PackageManifest manifest, boolean force) throws InstallException {
        for (PackageEntry pkg : manifest.allPackages()) {
            for (FileEntry file : pkg.files().values()) {
                Set<String> owners = database.getFileOwners(file.path());
                if (!owners.isEmpty()) {
                    if (force) {
                        logger.warn("File conflict (force): " + file.path() + " owned by " + owners + ", will overwrite");
                    } else {
                        throw new InstallException("File conflict: " + file.path() + " already owned by " + owners);
                    }
                }
            }
        }
    }

    private Map<String, FileEntry> extractPackageFiles(Path zipPath, PackageEntry pkgEntry) throws IOException {
        Map<String, FileEntry> extracted = new LinkedHashMap<>();
        
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            for (FileEntry fileEntry : pkgEntry.files().values()) {
                String zipPathStr = fileEntry.path();
                // In the ZIP, files are at the root or in a package-specific directory
                ZipEntry zipEntry = zip.getEntry(zipPathStr);
                if (zipEntry == null) {
                    // Try with package name prefix
                    zipEntry = zip.getEntry(pkgEntry.name() + "/" + zipPathStr);
                }
                if (zipEntry == null) {
                    throw new IOException("File not found in ZIP: " + zipPathStr);
                }

                Path destPath = installRoot.resolve(fileEntry.path());
                Files.createDirectories(destPath.getParent());

                // Extract with ZIP slip protection
                Path normalizedDest = destPath.normalize();
                if (!normalizedDest.startsWith(installRoot.normalize())) {
                    throw new IOException("ZIP slip attempt detected: " + fileEntry.path());
                }

                try (var is = zip.getInputStream(zipEntry);
                     var os = Files.newOutputStream(destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    is.transferTo(os);
                }

                // Verify extracted file
                long actualSize = Files.size(destPath);
                String actualSha256 = computeSha256(destPath);
                
                if (actualSize != fileEntry.size()) {
                    throw new IOException("Extracted file size mismatch: " + fileEntry.path() + 
                            " expected " + fileEntry.size() + ", got " + actualSize);
                }
                if (!actualSha256.equalsIgnoreCase(fileEntry.sha256())) {
                    throw new IOException("Extracted file hash mismatch: " + fileEntry.path() + 
                            " expected " + fileEntry.sha256() + ", got " + actualSha256);
                }

                // Update with actual verified info
                extracted.put(fileEntry.path(), new FileEntry(
                        fileEntry.path(), actualSize, actualSha256, pkgEntry.name()));
            }
        }

        return extracted;
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    public static class InstallException extends Exception {
        public InstallException(String message) {
            super(message);
        }
        public InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record PackageManifest(PackageEntry mainPackage, List<PackageEntry> additionalPackages, List<PackageEntry> allPackages) {}
}