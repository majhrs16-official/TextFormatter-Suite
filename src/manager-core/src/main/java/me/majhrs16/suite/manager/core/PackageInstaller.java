package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.spi.Version;

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
    private final TransactionLog transactionLog;

    public PackageInstaller(LocalPackageDatabase database, Path installRoot, PluginLogger logger) {
        this.database = database;
        this.installRoot = installRoot.toAbsolutePath();
        this.logger = logger;
        this.transactionLog = new TransactionLog(database.getManagerDir(), logger);
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
        String operationId = TransactionLog.generateOperationId();
        String mainPkgName = null;
        String mainPkgVersion = null;
        String mainPkgChannel = null;
        Path tempExtractDir = null;
        Map<PackageEntry, Map<String, FileEntry>> allExtractedFiles = new LinkedHashMap<>();
        List<PackageEntry> installedEntries = new ArrayList<>();
        
        try {
            logger.info("Installing package from: " + zipPath);
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, null, null, null, 
                    TransactionLog.OperationState.STARTED, "Starting installation from " + zipPath);
            
            // 1. Validate ZIP and read manifest
            PackageManifest manifest;
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                manifest = readManifest(zip);
            } catch (IOException e) {
                transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, null, null, null, 
                        TransactionLog.OperationState.FAILED, "Failed to read ZIP: " + e.getMessage());
                throw new InstallException("Failed to read ZIP: " + e.getMessage(), e);
            }
            
            mainPkgName = manifest.mainPackage().name();
            mainPkgVersion = manifest.mainPackage().version();
            mainPkgChannel = manifest.mainPackage().channel();
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, 
                    mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.EXTRACTING, "Reading manifest");
            
            // 2. Verify ZIP checksum against manifest
            String zipSha256;
            try {
                zipSha256 = computeSha256(zipPath);
            } catch (IOException e) {
                transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                        TransactionLog.OperationState.FAILED, "Failed to compute ZIP SHA256: " + e.getMessage());
                throw new InstallException("Failed to compute ZIP SHA256: " + e.getMessage(), e);
            }
            if (manifest.mainPackage().sha256() != null && !manifest.mainPackage().sha256().isBlank()) {
                if (!zipSha256.equalsIgnoreCase(manifest.mainPackage().sha256())) {
                    transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                            TransactionLog.OperationState.FAILED, "ZIP SHA256 mismatch");
                    throw new InstallException("ZIP SHA256 mismatch: expected " + manifest.mainPackage().sha256() + ", got " + zipSha256);
                }
            }

            // 3. Verify size
            long zipSize;
            try {
                zipSize = Files.size(zipPath);
            } catch (IOException e) {
                transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                        TransactionLog.OperationState.FAILED, "Failed to get ZIP size: " + e.getMessage());
                throw new InstallException("Failed to get ZIP size: " + e.getMessage(), e);
            }
            if (manifest.mainPackage().size() > 0 && zipSize != manifest.mainPackage().size()) {
                transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                        TransactionLog.OperationState.FAILED, "ZIP size mismatch");
                throw new InstallException("ZIP size mismatch: expected " + manifest.mainPackage().size() + ", got " + zipSize);
            }

            // 4. Check dependencies
            checkDependencies(manifest);

            // 5. Check for conflicts (file ownership)
            checkFileConflicts(manifest, force);

            // 6. Extract ALL files to temporary location FIRST (transactional)
            tempExtractDir = Files.createTempDirectory(database.getTmpDir(), "install-");
            
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.EXTRACTING, "Extracting files to temp directory");
            
            // Extract all packages to temp directory
            for (PackageEntry pkgEntry : manifest.allPackages()) {
                Map<String, FileEntry> extractedFiles = extractPackageFilesToTemp(zipPath, pkgEntry, tempExtractDir);
                allExtractedFiles.put(pkgEntry, extractedFiles);
            }

            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.EXTRACTED, "Files extracted successfully");

            // 7. Validate all extracted files against manifest
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.VALIDATING, "Validating extracted files");
            for (Map.Entry<PackageEntry, Map<String, FileEntry>> entry : allExtractedFiles.entrySet()) {
                validateExtractedFiles(entry.getKey(), entry.getValue());
            }
            
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.VALIDATED, "All files validated");

            // 8. ATOMIC COMMIT: Move all files from temp to final location
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.MOVING, "Moving files to final location");
            for (Map.Entry<PackageEntry, Map<String, FileEntry>> entry : allExtractedFiles.entrySet()) {
                moveFilesToFinalLocation(entry.getKey(), entry.getValue(), tempExtractDir, force);
            }
            
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.MOVED, "Files moved to final location");

            // 9. Register all packages in database
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.REGISTERING, "Registering packages");
            for (Map.Entry<PackageEntry, Map<String, FileEntry>> entry : allExtractedFiles.entrySet()) {
                PackageEntry pkgEntry = entry.getKey();
                Map<String, FileEntry> extractedFiles = entry.getValue();
                
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
                        zipSha256,
                        zipSize,
                        extractedFiles,
                        zipSha256,
                        pkgEntry.parentPackage(),
                        Map.of(
                                "Installed-Time", Instant.now().toString(),
                                "Status", "installed"
                        )
                );
                
                database.registerPackage(installedEntry);
                installedEntries.add(installedEntry);
                logger.info("Installed package: " + installedEntry.name() + " " + installedEntry.version() + " (" + installedEntry.channel() + ")");
            }
            
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.REGISTERED, "Packages registered in database");
            
            transactionLog.markCompleted(operationId);
            
            return installedEntries;

        } catch (InstallException e) {
            // Rollback on failure
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.FAILED, "Installation failed: " + e.getMessage());
            rollbackInstallation(installedEntries, allExtractedFiles, tempExtractDir);
            transactionLog.markFailed(operationId, e.getMessage());
            throw e;
        } catch (IOException e) {
            // Rollback on failure
            transactionLog.log(operationId, TransactionLog.OperationType.INSTALL, mainPkgName, mainPkgVersion, mainPkgChannel,
                    TransactionLog.OperationState.FAILED, "Installation failed: " + e.getMessage());
            rollbackInstallation(installedEntries, allExtractedFiles, tempExtractDir);
            transactionLog.markFailed(operationId, e.getMessage());
            throw new InstallException("Installation failed: " + e.getMessage(), e);
        } finally {
            // Cleanup temp directory
            if (tempExtractDir != null) {
                try {
                    deleteDirectory(tempExtractDir);
                } catch (IOException ignored) {}
            }
        }
    }
    
    /**
     * Rolls back a failed installation by removing installed packages and cleaning up files.
     */
    private void rollbackInstallation(List<PackageEntry> installedEntries, 
                                      Map<PackageEntry, Map<String, FileEntry>> allExtractedFiles,
                                      Path tempExtractDir) {
        logger.warn("Rolling back installation...");
        
        // Unregister packages that were registered
        for (PackageEntry entry : installedEntries) {
            try {
                database.unregisterPackage(entry.name(), entry.version(), entry.channel());
                logger.debug("Unregistered package during rollback: " + entry.name());
            } catch (IOException e) {
                logger.error("Failed to unregister package during rollback: " + entry.name() + " - " + e.getMessage());
            }
        }
        
        // Delete files that were moved to final location
        for (Map.Entry<PackageEntry, Map<String, FileEntry>> entry : allExtractedFiles.entrySet()) {
            for (FileEntry fileEntry : entry.getValue().values()) {
                Path filePath = installRoot.resolve(fileEntry.path());
                try {
                    if (Files.exists(filePath)) {
                        Files.delete(filePath);
                        logger.debug("Deleted file during rollback: " + fileEntry.path());
                    }
                } catch (IOException e) {
                    logger.error("Failed to delete file during rollback: " + fileEntry.path() + " - " + e.getMessage());
                }
            }
        }
        
        // Cleanup temp directory
        if (tempExtractDir != null) {
            try {
                deleteDirectory(tempExtractDir);
            } catch (IOException ignored) {}
        }
        
        logger.warn("Rollback completed");
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

        // FIRST: Unregister from database (atomic with save)
        try {
            database.unregisterPackage(entry.name(), entry.version(), entry.channel());
        } catch (IOException e) {
            throw new InstallException("Failed to unregister package: " + e.getMessage(), e);
        }

        // SECOND: Remove files that are exclusively owned by this package
        List<String> failedDeletions = new ArrayList<>();
        List<String> modifiedFiles = new ArrayList<>();
        for (Map.Entry<String, Set<String>> orphaned : orphanedFiles.entrySet()) {
            Path filePath = installRoot.resolve(orphaned.getKey());
            try {
                if (Files.exists(filePath)) {
                    // MGR-018: Check file integrity before deletion
                    // Verify the file hasn't been modified since installation
                    FileEntry fileEntry = entry.files().get(orphaned.getKey());
                    if (fileEntry != null) {
                        try {
                            long actualSize = Files.size(filePath);
                            String actualSha256 = computeSha256(filePath);
                            
                            if (actualSize != fileEntry.size() || !actualSha256.equalsIgnoreCase(fileEntry.sha256())) {
                                logger.warn("File modified since installation, skipping deletion: " + orphaned.getKey() + 
                                        " (expected size: " + fileEntry.size() + ", actual: " + actualSize + ")");
                                modifiedFiles.add(orphaned.getKey());
                                continue;
                            }
                        } catch (IOException e) {
                            logger.warn("Failed to verify file integrity for " + orphaned.getKey() + ": " + e.getMessage());
                        }
                    }
                    
                    Files.delete(filePath);
                    logger.debug("Removed file: " + orphaned.getKey());
                }
            } catch (IOException e) {
                logger.error("Failed to remove file " + orphaned.getKey() + ": " + e.getMessage());
                failedDeletions.add(orphaned.getKey());
            }
        }
        
        if (!modifiedFiles.isEmpty()) {
            logger.warn("Skipped deletion of modified files: " + modifiedFiles);
        }

        if (!failedDeletions.isEmpty()) {
            // Package already unregistered, but some files couldn't be deleted
            // This is a warning, not a failure - the package is no longer tracked
            logger.warn("Package unregistered but some files could not be deleted: " + failedDeletions);
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
                ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(dep);
                if (spec == null) continue;
                
                // Check if dependency is satisfied by installed packages
                boolean satisfied = false;
                for (PackageEntry installed : database.getAllPackages()) {
                    if (installed.name().equals(spec.name())) {
                        // Check version constraint
                        Version installedVersion = Version.parse(installed.version());
                        if (installedVersion.satisfies(spec.constraint())) {
                            satisfied = true;
                            break;
                        }
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
                        logger.warn("File conflict (force): " + file.path() + " owned by " + owners + ", will overwrite and transfer ownership");
                    } else {
                        throw new InstallException("File conflict: " + file.path() + " already owned by " + owners);
                    }
                }
            }
        }
    }

    private Map<String, FileEntry> extractPackageFilesToTemp(Path zipPath, PackageEntry pkgEntry, Path tempDir) throws IOException {
        Map<String, FileEntry> extracted = new LinkedHashMap<>();
        
        // Zip bomb protection: limit total extracted size
        long maxTotalExtractedSize = pkgEntry.files().values().stream()
                .mapToLong(FileEntry::size)
                .sum() + 10 * 1024 * 1024; // Allow 10MB overhead
        long totalExtractedSize = 0;
        
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

                Path destPath = tempDir.resolve(fileEntry.path());
                
                // ZIP slip protection: validate path BEFORE creating directories
                Path normalizedDest = destPath.normalize();
                Path normalizedTempDir = tempDir.normalize();
                if (!normalizedDest.startsWith(normalizedTempDir)) {
                    throw new IOException("ZIP slip attempt detected: " + fileEntry.path());
                }
                
                // Now safe to create directories
                Files.createDirectories(destPath.getParent());

                // Extract with size limit to prevent zip bomb
                long fileSizeLimit = fileEntry.size() + 1024 * 1024; // Allow 1MB overhead per file
                try (var is = zip.getInputStream(zipEntry);
                     var os = Files.newOutputStream(destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long bytesWritten = 0;
                    while ((read = is.read(buffer)) > 0) {
                        bytesWritten += read;
                        if (bytesWritten > fileSizeLimit) {
                            throw new IOException("Extracted file exceeds size limit: " + fileEntry.path() + 
                                    " (limit: " + fileSizeLimit + ")");
                        }
                        os.write(buffer, 0, read);
                    }
                }

                // Verify extracted file
                long actualSize = Files.size(destPath);
                totalExtractedSize += actualSize;
                if (totalExtractedSize > maxTotalExtractedSize) {
                    throw new IOException("Total extracted size exceeds limit: " + totalExtractedSize + 
                            " (limit: " + maxTotalExtractedSize + ")");
                }
                
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

    private void validateExtractedFiles(PackageEntry pkgEntry, Map<String, FileEntry> extractedFiles) throws IOException {
        // Verify all expected files are present
        for (FileEntry expectedFile : pkgEntry.files().values()) {
            FileEntry actualFile = extractedFiles.get(expectedFile.path());
            if (actualFile == null) {
                throw new IOException("Missing extracted file: " + expectedFile.path());
            }
            if (!actualFile.sha256().equalsIgnoreCase(expectedFile.sha256())) {
                throw new IOException("File hash mismatch after extraction: " + expectedFile.path());
            }
            if (actualFile.size() != expectedFile.size()) {
                throw new IOException("File size mismatch after extraction: " + expectedFile.path());
            }
        }
    }

    private void moveFilesToFinalLocation(PackageEntry pkgEntry, Map<String, FileEntry> extractedFiles, Path tempDir, boolean force) throws IOException {
        for (FileEntry fileEntry : extractedFiles.values()) {
            Path srcPath = tempDir.resolve(fileEntry.path());
            Path destPath = installRoot.resolve(fileEntry.path());
            
            // Symlink protection: resolve to real path and verify it's within installRoot
            Path realDestPath;
            try {
                realDestPath = destPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                // File doesn't exist yet, check parent
                realDestPath = destPath.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS).resolve(destPath.getFileName());
            }
            
            Path realInstallRoot = installRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realDestPath.startsWith(realInstallRoot)) {
                throw new IOException("Symlink escape detected: " + fileEntry.path() + " resolves outside install root");
            }
            
            Files.createDirectories(destPath.getParent());
            
            // Atomic move: copy then delete source
            Files.move(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        
        // If force install, transfer file ownership from old packages to new package
        if (force) {
            for (FileEntry fileEntry : extractedFiles.values()) {
                database.transferFileOwnership(fileEntry.path(), pkgEntry.id());
            }
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        logger.warn("Failed to delete temp file: " + path + " - " + e.getMessage());
                    }
                });
        }
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