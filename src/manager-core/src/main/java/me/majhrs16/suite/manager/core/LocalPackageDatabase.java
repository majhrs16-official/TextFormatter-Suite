package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.Version;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Local package database (dpkg equivalent) - manages library/status.
 * Thread-safe with read-write locking for concurrent access.
 */
public final class LocalPackageDatabase {

    private final Path managerDir;
    private final Path statusFile;
    private final Path packagesDir;
    private final Path dependenciesDir;
    private final Path tmpDir;
    private final Path listsDir;
    
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, PackageEntry> packages = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fileOwnership = new ConcurrentHashMap<>(); // file path -> package names
    private boolean loaded = false;
    private Instant lastModified;

    public LocalPackageDatabase(Path managerDir) {
        this.managerDir = managerDir.toAbsolutePath();
        this.statusFile = this.managerDir.resolve("library/status");
        this.packagesDir = this.managerDir.resolve("library/packages");
        this.dependenciesDir = this.managerDir.resolve("library/dependencies");
        this.tmpDir = this.managerDir.resolve("library/tmp");
        this.listsDir = this.managerDir.resolve("library/lists");
    }

    /**
     * Initializes the database directory structure and loads existing status.
     */
    public void initialize() throws IOException {
        lock.writeLock().lock();
        try {
            Files.createDirectories(packagesDir);
            Files.createDirectories(dependenciesDir);
            Files.createDirectories(tmpDir);
            Files.createDirectories(listsDir);
            Files.createDirectories(statusFile.getParent());
            
            if (Files.exists(statusFile)) {
                load();
            } else {
                // Create empty status file
                save();
            }
            loaded = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Loads the status database from disk.
     */
    public void load() throws IOException {
        lock.writeLock().lock();
        try {
            if (!Files.exists(statusFile)) {
                packages.clear();
                fileOwnership.clear();
                lastModified = Instant.now();
                return;
            }

            String content = Files.readString(statusFile);
            lastModified = Files.getLastModifiedTime(statusFile).toInstant();
            
            List<PackageEntry> entries = ManifestParser.parseManifest(content);
            packages.clear();
            fileOwnership.clear();
            
            for (PackageEntry entry : entries) {
                String key = entry.id();
                packages.put(key, entry);
                
                // Build file ownership map
                for (FileEntry file : entry.files().values()) {
                    fileOwnership.computeIfAbsent(file.path(), k -> ConcurrentHashMap.newKeySet())
                            .add(entry.name());
                }
            }
        } catch (ManifestParser.ParseException e) {
            throw new IOException("Failed to parse status database: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Saves the status database to disk atomically.
     */
    public void save() throws IOException {
        lock.writeLock().lock();
        try {
            // Write to temp file first
            Path tempFile = Files.createTempFile(statusFile.getParent(), "status", ".tmp");
            try {
                List<PackageEntry> entries = new ArrayList<>(packages.values());
                // Sort for consistent output
                entries.sort(Comparator.comparing(PackageEntry::name)
                        .thenComparing(e -> Version.parse(e.version()))
                        .thenComparing(PackageEntry::channel));
                
                String content = ManifestParser.serialize(entries);
                Files.writeString(tempFile, content);
                
                // Atomic move
                Files.move(tempFile, statusFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                lastModified = Instant.now();
            } catch (Exception e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if a package is installed.
     */
    public boolean isInstalled(String name, String version, String channel) {
        lock.readLock().lock();
        try {
            String key = name + "_" + version + "_" + channel;
            return packages.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if any version of a package is installed.
     */
    public boolean isInstalled(String name) {
        lock.readLock().lock();
        try {
            return packages.values().stream().anyMatch(p -> p.name().equals(name));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets an installed package by name, version, channel.
     */
    public Optional<PackageEntry> getPackage(String name, String version, String channel) {
        lock.readLock().lock();
        try {
            String key = name + "_" + version + "_" + channel;
            return Optional.ofNullable(packages.get(key));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the latest installed version of a package (by channel).
     */
    public Optional<PackageEntry> getLatestInstalled(String name, String channel) {
        lock.readLock().lock();
        try {
            return packages.values().stream()
                    .filter(p -> p.name().equals(name) && p.channel().equals(channel))
                    .max(Comparator.comparing(p -> Version.parse(p.version())));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets all installed packages.
     */
    public List<PackageEntry> getAllPackages() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(packages.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets packages by type.
     */
    public List<PackageEntry> getPackagesByType(me.majhrs16.suite.manager.spi.PackageType type) {
        lock.readLock().lock();
        try {
            return packages.values().stream()
                    .filter(p -> p.type() == type)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Registers a package as installed.
     */
    public void registerPackage(PackageEntry entry) throws IOException {
        lock.writeLock().lock();
        try {
            String key = entry.id();
            if (packages.containsKey(key)) {
                throw new IllegalStateException("Package already installed: " + key);
            }
            packages.put(key, entry);
            
            // Update file ownership
            for (FileEntry file : entry.files().values()) {
                fileOwnership.computeIfAbsent(file.path(), k -> ConcurrentHashMap.newKeySet())
                        .add(entry.name());
            }
            
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Unregisters a package (marks as removed, but keeps files for now).
     * Actual file removal is done by the caller after checking dependencies.
     */
    public void unregisterPackage(String name, String version, String channel) throws IOException {
        lock.writeLock().lock();
        try {
            String key = name + "_" + version + "_" + channel;
            PackageEntry entry = packages.remove(key);
            if (entry == null) {
                throw new IllegalStateException("Package not installed: " + key);
            }
            
            // Update file ownership
            for (FileEntry file : entry.files().values()) {
                Set<String> owners = fileOwnership.get(file.path());
                if (owners != null) {
                    owners.remove(entry.name());
                    if (owners.isEmpty()) {
                        fileOwnership.remove(file.path());
                    }
                }
            }
            
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if a file is owned by any installed package.
     */
    public boolean isFileOwned(String path) {
        lock.readLock().lock();
        try {
            return fileOwnership.containsKey(path);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the packages that own a file.
     */
    public Set<String> getFileOwners(String path) {
        lock.readLock().lock();
        try {
            Set<String> owners = fileOwnership.get(path);
            return owners != null ? Set.copyOf(owners) : Set.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if removing a package would leave files orphaned (owned only by that package).
     */
    public Map<String, Set<String>> checkOrphanedFiles(PackageEntry entry) {
        lock.readLock().lock();
        try {
            Map<String, Set<String>> orphaned = new HashMap<>();
            for (FileEntry file : entry.files().values()) {
                Set<String> owners = fileOwnership.get(file.path());
                if (owners != null && owners.size() == 1 && owners.contains(entry.name())) {
                    orphaned.put(file.path(), Set.copyOf(owners));
                }
            }
            return orphaned;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets packages that depend on the given package (reverse dependencies).
     */
    public List<PackageEntry> getReverseDependencies(String packageName) {
        lock.readLock().lock();
        try {
            return packages.values().stream()
                    .filter(p -> p.dependencies().contains(packageName))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Verifies integrity of installed package files.
     */
    public VerificationResult verifyPackage(PackageEntry entry, Path installRoot) {
        lock.readLock().lock();
        try {
            List<VerificationIssue> issues = new ArrayList<>();
            
            for (FileEntry file : entry.files().values()) {
                Path filePath = installRoot.resolve(file.path());
                
                if (!Files.exists(filePath)) {
                    issues.add(new VerificationIssue(file.path(), "MISSING", "File not found"));
                    continue;
                }
                
                try {
                    long actualSize = Files.size(filePath);
                    if (actualSize != file.size()) {
                        issues.add(new VerificationIssue(file.path(), "SIZE_MISMATCH", 
                                "Expected " + file.size() + " bytes, got " + actualSize));
                    }
                    
                    String actualSha256 = computeSha256(filePath);
                    if (!actualSha256.equalsIgnoreCase(file.sha256())) {
                        issues.add(new VerificationIssue(file.path(), "HASH_MISMATCH", 
                                "Expected " + file.sha256() + ", got " + actualSha256));
                    }
                } catch (IOException e) {
                    issues.add(new VerificationIssue(file.path(), "READ_ERROR", e.getMessage()));
                }
            }
            
            return new VerificationResult(entry.name(), issues.isEmpty(), issues);
        } finally {
            lock.readLock().unlock();
        }
    }

    private String computeSha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
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
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    public Path getPackagesDir() { return packagesDir; }
    public Path getDependenciesDir() { return dependenciesDir; }
    public Path getTmpDir() { return tmpDir; }
    public Path getListsDir() { return listsDir; }
    public Path getStatusFile() { return statusFile; }

    public boolean isLoaded() { return loaded; }

    public static class VerificationResult {
        private final String packageName;
        private final boolean valid;
        private final List<VerificationIssue> issues;

        public VerificationResult(String packageName, boolean valid, List<VerificationIssue> issues) {
            this.packageName = packageName;
            this.valid = valid;
            this.issues = issues;
        }

        public String packageName() { return packageName; }
        public boolean valid() { return valid; }
        public List<VerificationIssue> issues() { return issues; }
    }

    public static class VerificationIssue {
        private final String file;
        private final String type;
        private final String message;

        public VerificationIssue(String file, String type, String message) {
            this.file = file;
            this.type = type;
            this.message = message;
        }

        public String file() { return file; }
        public String type() { return type; }
        public String message() { return message; }
    }
}