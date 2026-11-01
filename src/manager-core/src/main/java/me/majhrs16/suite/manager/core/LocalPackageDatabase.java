package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.Version;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Local package database (dpkg equivalent) - manages library/status.
 *
 * <p>The status file is the authoritative installation pointer. Writes are
 * fsynced and published with an atomic rename, so a journal can use that rename
 * as its commit point.</p>
 */
public final class LocalPackageDatabase {

    private final Path managerDir;
    private final Path statusFile;
    private final Path packagesDir;
    private final Path dependenciesDir;
    private final Path tmpDir;
    private final Path listsDir;
    private final Path journalDir;
    private final Path backupDir;
    private final Path trashDir;
    private final Path cacheDir;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, PackageEntry> packages = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fileOwnership = new ConcurrentHashMap<>();
    private boolean loaded = false;
    private Instant lastModified;

    public LocalPackageDatabase(Path managerDir) {
        this.managerDir = managerDir.toAbsolutePath();
        this.statusFile = this.managerDir.resolve("library/status");
        this.packagesDir = this.managerDir.resolve("library/packages");
        this.dependenciesDir = this.managerDir.resolve("library/dependencies");
        this.tmpDir = this.managerDir.resolve("library/tmp");
        this.listsDir = this.managerDir.resolve("library/lists");
        this.journalDir = this.managerDir.resolve("library/journal");
        this.backupDir = this.managerDir.resolve("library/backup");
        this.trashDir = this.managerDir.resolve("library/trash");
        this.cacheDir = this.managerDir.resolve("library/cache");
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
            Files.createDirectories(journalDir);
            Files.createDirectories(backupDir);
            Files.createDirectories(trashDir);
            Files.createDirectories(cacheDir);
            Files.createDirectories(statusFile.getParent());

            if (Files.exists(statusFile)) {
                loadLocked();
            } else {
                commitImageLocked(imageOfLocked(List.of()));
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
            loadLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadLocked() throws IOException {
        if (!Files.exists(statusFile)) {
            packages.clear();
            fileOwnership.clear();
            lastModified = Instant.now();
            return;
        }

        byte[] content = Files.readAllBytes(statusFile);
        lastModified = Files.getLastModifiedTime(statusFile).toInstant();
        List<PackageEntry> entries;
        try {
            entries = ManifestParser.parseManifest(new String(content, java.nio.charset.StandardCharsets.UTF_8));
            validateIds(entries);
        } catch (ManifestParser.ParseException e) {
            throw new IOException("Failed to parse status database: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new IOException("Failed to parse status database: " + e.getMessage(), e);
        }
        replaceInMemoryLocked(entries);
    }

    /**
     * Saves the current in-memory state atomically.
     */
    public void save() throws IOException {
        lock.writeLock().lock();
        try {
            commitImageLocked(imageOfLocked(packages.values()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Immutable serialized status image used as a journal snapshot. */
    public record StatusImage(byte[] content, String sha256, List<PackageEntry> entries) {
        public StatusImage {
            content = content.clone();
            entries = List.copyOf(entries);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public StatusImage imageOf(Collection<PackageEntry> entries) {
        lock.readLock().lock();
        try {
            return imageOfLocked(entries);
        } finally {
            lock.readLock().unlock();
        }
    }

    public StatusImage currentImage() throws IOException {
        lock.readLock().lock();
        try {
            byte[] content = Files.exists(statusFile) ? Files.readAllBytes(statusFile) : new byte[0];
            return new StatusImage(content, IoSupport.sha256(content),
                    new ArrayList<>(packages.values()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public byte[] readStatusBytes() throws IOException {
        lock.readLock().lock();
        try {
            return Files.exists(statusFile) ? Files.readAllBytes(statusFile) : new byte[0];
        } finally {
            lock.readLock().unlock();
        }
    }

    public String currentStatusSha256() throws IOException {
        return IoSupport.sha256(readStatusBytes());
    }

    /**
     * Publishes a prepared status image. The atomic rename inside this method is
     * the commit point of a package transaction.
     */
    public void commitImage(StatusImage image) throws IOException {
        lock.writeLock().lock();
        try {
            commitImageLocked(image);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Restores raw status bytes and reloads the in-memory inventory. */
    public void restoreStatus(byte[] content) throws IOException {
        lock.writeLock().lock();
        try {
            List<PackageEntry> entries;
            try {
                entries = ManifestParser.parseManifest(new String(content, java.nio.charset.StandardCharsets.UTF_8));
                validateIds(entries);
            } catch (ManifestParser.ParseException e) {
                throw new IOException("Cannot restore invalid status snapshot: " + e.getMessage(), e);
            } catch (IllegalStateException e) {
                throw new IOException("Cannot restore invalid status snapshot: " + e.getMessage(), e);
            }
            IoSupport.atomicWrite(statusFile, content);
            replaceInMemoryLocked(entries);
            lastModified = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private StatusImage imageOfLocked(Collection<PackageEntry> source) {
        List<PackageEntry> entries = new ArrayList<>(source);
        validateIds(entries);
        entries.sort(Comparator.comparing(PackageEntry::name)
                .thenComparing(e -> Version.parse(e.version()))
                .thenComparing(PackageEntry::channel));
        byte[] content = ManifestParser.serialize(entries)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            return new StatusImage(content, IoSupport.sha256(content), entries);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot hash status image", failure);
        }
    }

    private void commitImageLocked(StatusImage image) throws IOException {
        validateIds(image.entries());
        IoSupport.atomicWrite(statusFile, image.content());
        replaceInMemoryLocked(image.entries());
        lastModified = Instant.now();
    }

    private static void validateIds(Collection<PackageEntry> entries) {
        Set<String> ids = new HashSet<>();
        for (PackageEntry entry : entries) {
            if (!ids.add(entry.id())) {
                throw new IllegalStateException("Duplicate package id in status: " + entry.id());
            }
        }
    }

    private void replaceInMemoryLocked(Collection<PackageEntry> entries) {
        packages.clear();
        fileOwnership.clear();
        for (PackageEntry entry : entries) {
            packages.put(entry.id(), entry);
            for (FileEntry file : entry.files().values()) {
                fileOwnership.computeIfAbsent(file.path(), k -> ConcurrentHashMap.newKeySet())
                        .add(entry.id());
            }
        }
    }

    /**
     * Checks if a package is installed.
     */
    public boolean isInstalled(String name, String version, String channel) {
        lock.readLock().lock();
        try {
            return packages.containsKey(name + "_" + version + "_" + channel);
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

    public Optional<PackageEntry> getPackage(String name, String version, String channel) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(packages.get(name + "_" + version + "_" + channel));
        } finally {
            lock.readLock().unlock();
        }
    }

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

    public Optional<PackageEntry> findInstalledProvider(String name) {
        lock.readLock().lock();
        try {
            return packages.values().stream()
                    .filter(p -> p.name().equals(name) || p.provides().contains(name))
                    .max(Comparator.comparing(p -> Version.parse(p.version())));
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isDependencySatisfied(String name, String constraint) {
        lock.readLock().lock();
        try {
            return packages.values().stream()
                    .filter(p -> p.name().equals(name) || p.provides().contains(name))
                    .anyMatch(p -> Version.parse(p.version()).satisfies(constraint));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PackageEntry> findConflicts(PackageEntry candidate) {
        lock.readLock().lock();
        try {
            return packages.values().stream()
                    .filter(installed -> !installed.name().equals(candidate.name()))
                    .filter(installed -> conflicts(candidate, installed))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public static boolean conflicts(PackageEntry first, PackageEntry second) {
        if (first.name().equals(second.name())) {
            return false;
        }
        return excludes(first, second) || excludes(second, first);
    }

    private static boolean excludes(PackageEntry source, PackageEntry target) {
        for (String excluded : source.excludes()) {
            if (excluded.equals(target.name()) || target.provides().contains(excluded)) {
                return true;
            }
        }
        return false;
    }

    public List<PackageEntry> getAllPackages() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(packages.values());
        } finally {
            lock.readLock().unlock();
        }
    }

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

    public void registerPackage(PackageEntry entry) throws IOException {
        lock.writeLock().lock();
        try {
            if (packages.containsKey(entry.id())) {
                throw new IllegalStateException("Package already installed: " + entry.id());
            }
            Map<String, PackageEntry> next = new LinkedHashMap<>();
            for (PackageEntry installed : packages.values()) {
                next.put(installed.id(), installed);
            }
            next.put(entry.id(), entry);
            commitImageLocked(imageOfLocked(next.values()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void unregisterPackage(String name, String version, String channel) throws IOException {
        lock.writeLock().lock();
        try {
            String key = name + "_" + version + "_" + channel;
            if (!packages.containsKey(key)) {
                throw new IllegalStateException("Package not installed: " + key);
            }
            Map<String, PackageEntry> next = new LinkedHashMap<>();
            for (PackageEntry installed : packages.values()) {
                if (!installed.id().equals(key)) {
                    next.put(installed.id(), installed);
                }
            }
            commitImageLocked(imageOfLocked(next.values()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isFileOwned(String path) {
        lock.readLock().lock();
        try {
            return fileOwnership.containsKey(path);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<String> getFileOwners(String path) {
        lock.readLock().lock();
        try {
            Set<String> owners = fileOwnership.get(path);
            return owners != null ? Set.copyOf(owners) : Set.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<String, Set<String>> checkOrphanedFiles(PackageEntry entry) {
        lock.readLock().lock();
        try {
            Map<String, Set<String>> orphaned = new HashMap<>();
            for (FileEntry file : entry.files().values()) {
                Set<String> owners = fileOwnership.get(file.path());
                if (owners != null && owners.size() == 1 && owners.contains(entry.id())) {
                    orphaned.put(file.path(), Set.copyOf(owners));
                }
            }
            return orphaned;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PackageEntry> getReverseDependencies(String packageName) {
        lock.readLock().lock();
        try {
            Set<String> suppliedNames = new HashSet<>();
            suppliedNames.add(packageName);
            packages.values().stream()
                    .filter(p -> p.name().equals(packageName))
                    .forEach(p -> suppliedNames.addAll(p.provides()));

            List<PackageEntry> reverse = new ArrayList<>();
            for (PackageEntry dependent : packages.values()) {
                if (dependent.name().equals(packageName)) {
                    continue;
                }
                for (String dependency : dependent.dependencies()) {
                    ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(dependency);
                    if (spec == null || !suppliedNames.contains(spec.name())) {
                        continue;
                    }
                    if (!isDependencySatisfiedExcluding(spec.name(), spec.constraint(), packageName)) {
                        reverse.add(dependent);
                        break;
                    }
                }
            }
            return reverse;
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean isDependencySatisfiedExcluding(String dependencyName, String constraint, String excludedName) {
        for (PackageEntry p : packages.values()) {
            if (p.name().equals(excludedName)) {
                continue;
            }
            if ((p.name().equals(dependencyName) || p.provides().contains(dependencyName))
                    && Version.parse(p.version()).satisfies(constraint)) {
                return true;
            }
        }
        return false;
    }

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

                    String actualSha256 = IoSupport.sha256(filePath);
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

    public Path getPackagesDir() { return packagesDir; }
    public Path getDependenciesDir() { return dependenciesDir; }
    public Path getTmpDir() { return tmpDir; }
    public Path getListsDir() { return listsDir; }
    public Path getStatusFile() { return statusFile; }
    public Path getManagerDir() { return managerDir; }
    public Path getJournalDir() { return journalDir; }
    public Path getBackupDir() { return backupDir; }
    public Path getTrashDir() { return trashDir; }
    public Path getCacheDir() { return cacheDir; }

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
