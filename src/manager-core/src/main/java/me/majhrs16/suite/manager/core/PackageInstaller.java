package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.spi.Version;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Package installer (dpkg equivalent) - handles installation from local ZIP
 * artifacts.
 *
 * <p>A batch is validated and extracted before the first mutation. The journal
 * then records every file action, {@code library/status} is committed with one
 * atomic rename, and only after that point is the installation considered
 * installed. The manager never activates modules.</p>
 */
public final class PackageInstaller {

    private final LocalPackageDatabase database;
    private final Path installRoot;
    private final PluginLogger logger;

    public PackageInstaller(LocalPackageDatabase database, Path installRoot, PluginLogger logger) {
        this.database = database;
        this.installRoot = installRoot.toAbsolutePath().normalize();
        this.logger = logger;
    }

    /**
     * Recovers every incomplete journal before the manager starts serving
     * operations. This is the crash path for an interrupted stage-2 commit:
     * journals before the status rename restore files/status, journals after it
     * only finish cleanup.
     */
    public void recoverIncompleteOperations() throws InstallException {
        try {
            PackageJournal.recoverAll(database, installRoot, logger);
        } catch (IOException failure) {
            throw new InstallException("Cannot recover package journals: "
                    + failure.getMessage(), failure);
        }
    }

    public List<PackageEntry> install(Path zipPath, boolean force) throws InstallException {
        return installBatch(List.of(zipPath), force, List.of());
    }

    /**
     * Installs several distribution ZIPs in one file/status transaction.
     */
    public List<PackageEntry> installBatch(List<Path> zipPaths, boolean force) throws InstallException {
        return installBatch(zipPaths, force, List.of());
    }

    /**
     * Installs several distribution ZIPs and also removes packages that the
     * resolver explicitly replaced because of an {@code Exclude} conflict.
     *
     * <p>An upgrade of the same package name/channel only changes the pointer;
     * its previous version directory is deliberately kept on disk and is never
     * reused as the destination of the new version.</p>
     */
    public List<PackageEntry> installBatch(List<Path> zipPaths, boolean force,
                                           Collection<PackageEntry> replaceBefore)
            throws InstallException {
        return installBatch(zipPaths, force, replaceBefore, () -> {
        });
    }

    /**
     * Installs several distribution ZIPs after running a runtime barrier.
     *
     * <p>The barrier runs after validation/extraction and before the journal or
     * any installed file is touched. It is used to deactivate packages that an
     * {@code Exclude} replacement removes, so their JARs are not moved while a
     * classloader may still need them.</p>
     */
    public List<PackageEntry> installBatch(List<Path> zipPaths, boolean force,
                                           Collection<PackageEntry> replaceBefore,
                                           Runnable beforeMutation)
            throws InstallException {
        if (zipPaths == null || zipPaths.isEmpty()) {
            throw new InstallException("No distribution ZIPs supplied");
        }
        Objects.requireNonNull(beforeMutation, "beforeMutation");
        String operationId = IoSupport.operationId();
        Path tempRoot = database.getTmpDir().resolve(operationId);
        PackageJournal journal = null;
        boolean committed = false;
        List<PackageEntry> installed = new ArrayList<>();
        LocalPackageDatabase.StatusImage newImage = null;

        try {
            List<PreparedZip> prepared = prepareZips(zipPaths);
            List<PackageEntry> newEntries = prepared.stream()
                    .flatMap(zip -> zip.manifest().allPackages().stream())
                    .toList();
            validateBatch(newEntries, replaceBefore, force);

            Files.createDirectories(tempRoot);
            for (PreparedZip zip : prepared) {
                for (PackageEntry entry : zip.manifest().allPackages()) {
                    extractPackageFilesToTemp(zip.path(), entry, tempRoot);
                }
            }

            for (PreparedZip zip : prepared) {
                for (PackageEntry entry : zip.manifest().allPackages()) {
                    installed.add(installedEntry(entry, zip, tempRoot));
                }
            }

            try {
                beforeMutation.run();
            } catch (RuntimeException failure) {
                throw new InstallException("Runtime barrier rejected installation: "
                        + failure.getMessage(), failure);
            }

            Collection<PackageEntry> removals = replaceBefore;
            Map<String, PackageEntry> next = nextStatus(installed, removals);
            newImage = database.imageOf(next.values());
            LocalPackageDatabase.StatusImage oldImage = database.currentImage();
            journal = PackageJournal.begin(installRoot, database, operationId,
                    PackageJournal.OperationType.INSTALL, oldImage, newImage);

            for (PreparedZip zip : prepared) {
                for (PackageEntry entry : zip.manifest().allPackages()) {
                    moveInstalledFiles(entry, tempRoot, operationId, journal);
                }
            }
            moveReplacedPackages(replaceBefore, newEntries, operationId, journal);

            journal.committing();
            database.commitImage(newImage);
            committed = true;
            try {
                journal.committed();
            } catch (IOException failure) {
                // The status rename already happened. Recovery will see the
                // COMMITTING record and the new status hash as committed.
                logger.warn("Cannot append COMMITTED marker for operation " + operationId
                        + ": " + failure.getMessage());
            }
            journal.close();
            try {
                IoSupport.deleteRecursively(database.getBackupDir().resolve(operationId));
            } catch (IOException failure) {
                logger.warn("Cannot remove package backup for operation " + operationId
                        + ": " + failure.getMessage());
            }

            logger.info("Installed " + installed.size() + " package(s) in operation " + operationId);
            return List.copyOf(installed);
        } catch (InstallException failure) {
            rollback(journal, committed, newImage, operationId, failure);
            throw failure;
        } catch (Exception failure) {
            InstallException wrapped = new InstallException("Installation failed: " + failure.getMessage(), failure);
            rollback(journal, committed, newImage, operationId, wrapped);
            throw wrapped;
        } finally {
            try {
                IoSupport.deleteRecursively(tempRoot);
            } catch (IOException failure) {
                logger.warn("Failed to remove package temporary directory " + tempRoot + ": "
                        + failure.getMessage());
            }
        }
    }

    private void rollback(PackageJournal journal, boolean committed,
                          LocalPackageDatabase.StatusImage newImage, String operationId,
                          InstallException failure) {
        if (journal == null) {
            return;
        }
        try {
            journal.close();
            if (committed || (newImage != null
                    && database.currentStatusSha256().equalsIgnoreCase(newImage.sha256()))) {
                logger.warn("Operation " + operationId
                        + " was already committed; keeping the installed files: "
                        + failure.getMessage());
                return;
            }
            logger.warn("Rolling back package operation " + operationId);
            PackageJournal.recoverAll(database, installRoot, logger);
        } catch (Exception rollbackFailure) {
            logger.error("Rollback of operation " + operationId + " failed: "
                    + rollbackFailure.getMessage(), rollbackFailure);
        }
    }

    private record PreparedZip(Path path, String sha256, long size, PackageManifest manifest) {
    }

    private List<PreparedZip> prepareZips(List<Path> zipPaths) throws IOException, InstallException {
        List<PreparedZip> prepared = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Path zipPath : zipPaths) {
            PackageManifest manifest;
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                manifest = readManifest(zip);
            }
            String sha256 = IoSupport.sha256(zipPath);
            long size = Files.size(zipPath);
            PackageEntry main = manifest.mainPackage();
            if (main.sha256() != null && !main.sha256().isBlank()
                    && !sha256.equalsIgnoreCase(main.sha256())) {
                throw new InstallException("ZIP SHA256 mismatch: expected "
                        + main.sha256() + ", got " + sha256);
            }
            if (main.size() > 0 && size != main.size()) {
                throw new InstallException("ZIP size mismatch: expected "
                        + main.size() + ", got " + size);
            }
            for (PackageEntry entry : manifest.allPackages()) {
                if (!names.add(entry.name())) {
                    throw new InstallException("Duplicate package name in batch: " + entry.name());
                }
                if (!ids.add(entry.id())) {
                    throw new InstallException("Duplicate package name in batch: " + entry.id());
                }
                for (FileEntry file : entry.files().values()) {
                    PackageLayout.validate(entry, file.path());
                }
            }
            prepared.add(new PreparedZip(zipPath, sha256, size, manifest));
        }
        return prepared;
    }

    private void validateBatch(List<PackageEntry> newEntries,
                               Collection<PackageEntry> replaceBefore, boolean force)
            throws InstallException {
        for (PackageEntry entry : newEntries) {
            boolean sameVersionInstalled = database.getAllPackages().stream()
                    .anyMatch(old -> old.type() == entry.type()
                            && old.name().equals(entry.name())
                            && old.version().equals(entry.version()));
            if (sameVersionInstalled) {
                throw new InstallException("Package " + entry.name() + " " + entry.version()
                        + " is already installed; remove it before reinstalling");
            }
        }
        Set<String> removedIds = new HashSet<>();
        for (PackageEntry entry : replaceBefore) {
            removedIds.add(entry.id());
        }
        for (PackageEntry entry : newEntries) {
            removedIds.addAll(database.getAllPackages().stream()
                    .filter(old -> old.name().equals(entry.name())
                            && old.channel().equals(entry.channel()))
                    .map(PackageEntry::id)
                    .toList());
        }

        for (PackageEntry entry : newEntries) {
            for (String dependency : entry.dependencies()) {
                ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(dependency);
                if (spec == null) {
                    continue;
                }
                if (!satisfied(spec, newEntries, removedIds)) {
                    throw new InstallException("Unsatisfied dependency: " + entry.name()
                            + " requires " + dependency);
                }
            }
        }

        for (int i = 0; i < newEntries.size(); i++) {
            for (int j = i + 1; j < newEntries.size(); j++) {
                PackageEntry first = newEntries.get(i);
                PackageEntry second = newEntries.get(j);
                if (LocalPackageDatabase.conflicts(first, second)) {
                    if (!force) {
                        throw new InstallException("Package conflict inside batch: "
                                + first.name() + " cannot coexist with " + second.name());
                    }
                    logger.warn("Package conflict inside batch (force): " + first.name()
                            + " cannot coexist with " + second.name());
                }
            }
            for (PackageEntry installed : database.getAllPackages()) {
                if (removedIds.contains(installed.id())) {
                    continue;
                }
                if (LocalPackageDatabase.conflicts(newEntries.get(i), installed)) {
                    if (!force) {
                        throw new InstallException("Package conflict: " + newEntries.get(i).name()
                                + " cannot coexist with " + installed.name() + " "
                                + installed.version() + " (Exclude).");
                    }
                    logger.warn("Package conflict (force): " + newEntries.get(i).name()
                            + " cannot coexist with " + installed.name() + " "
                            + installed.version());
                }
            }
        }

        Set<String> paths = new HashSet<>();
        for (PackageEntry entry : newEntries) {
            for (FileEntry file : entry.files().values()) {
                if (!paths.add(file.path())) {
                    throw new InstallException("Duplicate file in batch: " + file.path());
                }
                Set<String> owners = new HashSet<>(database.getFileOwners(file.path()));
                owners.removeAll(removedIds);
                if (!owners.isEmpty()) {
                    if (!force) {
                        throw new InstallException("File conflict: " + file.path()
                                + " already owned by " + owners);
                    }
                    logger.warn("File conflict (force): " + file.path()
                            + " owned by " + owners + "; ownership will transfer");
                }
            }
        }
    }

    private boolean satisfied(ManifestParser.DependencySpec spec, List<PackageEntry> newEntries,
                              Set<String> removedIds) {
        for (PackageEntry candidate : newEntries) {
            if ((candidate.name().equals(spec.name()) || candidate.provides().contains(spec.name()))
                    && Version.parse(candidate.version()).satisfies(spec.constraint())) {
                return true;
            }
        }
        return database.getAllPackages().stream()
                .filter(entry -> !removedIds.contains(entry.id()))
                .filter(entry -> entry.name().equals(spec.name())
                        || entry.provides().contains(spec.name()))
                .anyMatch(entry -> Version.parse(entry.version()).satisfies(spec.constraint()));
    }

    private Map<String, PackageEntry> nextStatus(List<PackageEntry> newEntries,
                                                 Collection<PackageEntry> replaceBefore) {
        Set<String> replaceNames = new HashSet<>();
        for (PackageEntry entry : newEntries) {
            replaceNames.add(entry.name() + "\0" + entry.channel());
        }
        Set<String> removedIds = replaceBefore.stream()
                .map(PackageEntry::id)
                .collect(Collectors.toSet());

        Map<String, PackageEntry> next = new LinkedHashMap<>();
        for (PackageEntry old : database.getAllPackages()) {
            if (removedIds.contains(old.id())
                    || replaceNames.contains(old.name() + "\0" + old.channel())) {
                continue;
            }
            next.put(old.id(), old);
        }

        // Forced ownership transfer: a non-versioned file can only have one owner
        // in the authoritative status so removal of the new package cannot leave
        // a stale owner behind.
        Set<String> newPaths = new HashSet<>();
        for (PackageEntry entry : newEntries) {
            newPaths.addAll(entry.files().keySet());
        }
        Map<String, PackageEntry> transferred = new LinkedHashMap<>();
        for (PackageEntry old : next.values()) {
            Map<String, FileEntry> kept = new LinkedHashMap<>();
            for (Map.Entry<String, FileEntry> file : old.files().entrySet()) {
                if (!newPaths.contains(file.getKey())) {
                    kept.put(file.getKey(), file.getValue());
                }
            }
            transferred.put(old.id(), kept.size() == old.files().size()
                    ? old : copyWithFiles(old, kept));
        }
        next.clear();
        next.putAll(transferred);
        for (PackageEntry entry : newEntries) {
            next.put(entry.id(), entry);
        }
        return next;
    }

    private static PackageEntry copyWithFiles(PackageEntry entry, Map<String, FileEntry> files) {
        return new PackageEntry(entry.name(), entry.version(), entry.channel(), entry.type(),
                entry.dependencies(), entry.excludes(), entry.provides(), entry.arch(),
                entry.platform(), entry.url(), entry.os(), entry.sha256(), entry.size(),
                files, entry.sourceArtifact(), entry.parentPackage(), entry.extraFields());
    }

    private PackageEntry installedEntry(PackageEntry entry, PreparedZip zip, Path tempRoot)
            throws IOException {
        Map<String, FileEntry> files = new LinkedHashMap<>();
        for (FileEntry expected : entry.files().values()) {
            Path source = tempRoot.resolve(expected.path()).normalize();
            long size = Files.size(source);
            String sha256 = IoSupport.sha256(source);
            files.put(expected.path(), new FileEntry(expected.path(), size, sha256, entry.name()));
        }
        return new PackageEntry(entry.name(), entry.version(), entry.channel(), entry.type(),
                entry.dependencies(), entry.excludes(), entry.provides(), entry.arch(),
                entry.platform(), entry.url(), entry.os(), zip.sha256(), zip.size(), files,
                zip.sha256(), entry.parentPackage(),
                Map.of("Installed-Time", Instant.now().toString(), "Status", "installed"));
    }

    private void moveInstalledFiles(PackageEntry entry, Path tempRoot, String operationId,
                                    PackageJournal journal) throws IOException {
        Path backupRoot = database.getBackupDir().resolve(operationId);
        for (FileEntry file : entry.files().values()) {
            Path source = PackageLayout.resolve(tempRoot, file.path());
            Path destination = PackageLayout.resolve(installRoot, file.path());
            if (Files.exists(destination)) {
                Path backup = PackageLayout.resolve(backupRoot,
                        installRoot.relativize(destination).toString());
                journal.replaced(destination, backup);
                IoSupport.move(destination, backup);
            } else {
                journal.created(destination);
            }
            IoSupport.move(source, destination);
        }
    }

    private void moveReplacedPackages(Collection<PackageEntry> replaceBefore,
                                      List<PackageEntry> newEntries, String operationId,
                                      PackageJournal journal) throws IOException {
        Set<String> newNames = newEntries.stream().map(PackageEntry::name).collect(Collectors.toSet());
        Set<String> newPaths = newEntries.stream()
                .flatMap(entry -> entry.files().keySet().stream())
                .collect(Collectors.toSet());
        Path trashRoot = database.getTrashDir().resolve(operationId);
        for (PackageEntry old : replaceBefore) {
            if (newNames.contains(old.name())) {
                continue;
            }
            for (FileEntry file : old.files().values()) {
                // A forced replacement may reuse a path owned by the old package.
                // The new package now owns it; moving it to trash would delete the
                // file that was just installed.
                if (newPaths.contains(file.path())) {
                    continue;
                }
                if (!fileExclusiveTo(file.path(), replaceBefore)) {
                    logger.warn("Keeping shared file " + file.path()
                            + " while replacing " + old.name());
                    continue;
                }
                Path path = PackageLayout.resolve(installRoot, file.path());
                if (!Files.exists(path)) {
                    continue;
                }
                Path trash = PackageLayout.resolve(trashRoot,
                        installRoot.relativize(path).toString());
                journal.removed(path, trash);
                IoSupport.move(path, trash);
            }
        }
    }

    /**
     * Removes a package and optionally unused dependency packages in one
     * transaction. The barrier runs before any file or status mutation.
     */
    public void remove(String name, String version, String channel,
                       boolean autoRemoveDependencies) throws InstallException {
        remove(name, version, channel, autoRemoveDependencies, () -> {
        });
    }

    public void remove(String name, String version, String channel,
                       boolean autoRemoveDependencies, Runnable barrier) throws InstallException {
        String operationId = IoSupport.operationId();
        PackageJournal journal = null;
        boolean committed = false;
        LocalPackageDatabase.StatusImage newImage = null;
        try {
            List<PackageEntry> targets = removalTargets(name, version, channel, autoRemoveDependencies);
            validateRemoval(targets);
            try {
                barrier.run();
            } catch (RuntimeException failure) {
                throw new InstallException("Runtime barrier rejected removal: "
                        + failure.getMessage(), failure);
            }

            Map<String, PackageEntry> next = new LinkedHashMap<>();
            for (PackageEntry installed : database.getAllPackages()) {
                next.put(installed.id(), installed);
            }
            for (PackageEntry target : targets) {
                next.remove(target.id());
            }
            newImage = database.imageOf(next.values());
            LocalPackageDatabase.StatusImage oldImage = database.currentImage();
            journal = PackageJournal.begin(installRoot, database, operationId,
                    PackageJournal.OperationType.REMOVE, oldImage, newImage);

            Path trashRoot = database.getTrashDir().resolve(operationId);
            for (PackageEntry target : targets) {
                for (FileEntry file : target.files().values()) {
                    Path path = PackageLayout.resolve(installRoot, file.path());
                    if (!Files.exists(path)) {
                        continue;
                    }
                    if (!fileExclusiveTo(file.path(), targets)) {
                        logger.warn("Keeping shared file " + file.path()
                                + " while removing " + target.name());
                        continue;
                    }
                    if (!matchesInstalledFile(path, file)) {
                        logger.warn("File modified since installation, keeping it in place: "
                                + file.path());
                        continue;
                    }
                    Path trash = PackageLayout.resolve(trashRoot,
                            installRoot.relativize(path).toString());
                    journal.removed(path, trash);
                    IoSupport.move(path, trash);
                }
            }

            journal.committing();
            database.commitImage(newImage);
            committed = true;
            try {
                journal.committed();
            } catch (IOException failure) {
                logger.warn("Cannot append COMMITTED marker for operation " + operationId
                        + ": " + failure.getMessage());
            }
            journal.close();
            try {
                IoSupport.deleteRecursively(database.getBackupDir().resolve(operationId));
            } catch (IOException failure) {
                logger.warn("Cannot remove package backup for operation " + operationId
                        + ": " + failure.getMessage());
            }
            for (PackageEntry target : targets) {
                cleanupVersionDirectory(target);
            }
            logger.info("Removed package operation " + operationId + ": "
                    + targets.stream().map(PackageEntry::name).collect(Collectors.joining(", ")));
        } catch (InstallException failure) {
            rollback(journal, committed, newImage, operationId, failure);
            throw failure;
        } catch (Exception failure) {
            InstallException wrapped = new InstallException("Removal failed: " + failure.getMessage(), failure);
            rollback(journal, committed, newImage, operationId, wrapped);
            throw wrapped;
        }
    }

    private boolean fileExclusiveTo(String path, Collection<PackageEntry> targets) {
        Set<String> removedIds = targets.stream().map(PackageEntry::id).collect(Collectors.toSet());
        Set<String> owners = new HashSet<>(database.getFileOwners(path));
        owners.removeAll(removedIds);
        return owners.isEmpty();
    }

    private boolean matchesInstalledFile(Path path, FileEntry file) {
        try {
            return Files.size(path) == file.size()
                    && IoSupport.sha256(path).equalsIgnoreCase(file.sha256());
        } catch (IOException failure) {
            return false;
        }
    }

    private List<PackageEntry> removalTargets(String name, String version, String channel,
                                              boolean autoRemoveDependencies) throws InstallException {
        PackageEntry entry;
        if ("any".equals(version)) {
            entry = database.getLatestInstalled(name, channel)
                    .orElseThrow(() -> new InstallException("Package not installed: "
                            + name + " (" + channel + ")"));
        } else {
            entry = database.getPackage(name, version, channel)
                    .orElseThrow(() -> new InstallException("Package not installed: "
                            + name + " " + version + " (" + channel + ")"));
        }
        List<PackageEntry> targets = new ArrayList<>();
        targets.add(entry);
        if (!autoRemoveDependencies) {
            return targets;
        }

        boolean progress = true;
        while (progress) {
            progress = false;
            Set<String> removedIds = targets.stream().map(PackageEntry::id).collect(Collectors.toSet());
            for (PackageEntry dependency : database.getPackagesByType(PackageType.DEPENDENCY)) {
                if (removedIds.contains(dependency.id())) {
                    continue;
                }
                boolean needed = false;
                for (PackageEntry installed : database.getAllPackages()) {
                    if (removedIds.contains(installed.id())) {
                        continue;
                    }
                    for (String declared : installed.dependencies()) {
                        ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(declared);
                        if (spec != null && (spec.name().equals(dependency.name())
                                || dependency.provides().contains(spec.name()))) {
                            needed = true;
                            break;
                        }
                    }
                    if (needed) {
                        break;
                    }
                }
                if (!needed) {
                    targets.add(dependency);
                    progress = true;
                }
            }
        }
        return targets;
    }

    private void validateRemoval(List<PackageEntry> targets) throws InstallException {
        Set<String> removedIds = targets.stream().map(PackageEntry::id).collect(Collectors.toSet());
        Set<String> supplied = new HashSet<>();
        for (PackageEntry target : targets) {
            supplied.add(target.name());
            supplied.addAll(target.provides());
        }
        for (PackageEntry installed : database.getAllPackages()) {
            if (removedIds.contains(installed.id())) {
                continue;
            }
            for (String declared : installed.dependencies()) {
                ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(declared);
                if (spec == null || !supplied.contains(spec.name())) {
                    continue;
                }
                boolean alternative = database.getAllPackages().stream()
                        .filter(entry -> !removedIds.contains(entry.id()))
                        .filter(entry -> entry.name().equals(spec.name())
                                || entry.provides().contains(spec.name()))
                        .anyMatch(entry -> Version.parse(entry.version()).satisfies(spec.constraint()));
                if (!alternative) {
                    String removed = targets.stream()
                            .filter(target -> target.name().equals(spec.name())
                                    || target.provides().contains(spec.name()))
                            .map(PackageEntry::name)
                            .collect(Collectors.joining(", "));
                    throw new InstallException("Cannot remove " + removed
                            + ": required by " + installed.name() + " " + installed.version());
                }
            }
        }
    }

    private void cleanupVersionDirectory(PackageEntry entry) {
        try {
            Path versionRoot = PackageLayout.versionRoot(installRoot, entry);
            IoSupport.deleteEmptyParents(versionRoot, installRoot);
        } catch (IOException failure) {
            logger.debug("Cannot clean version directory for " + entry.name() + ": "
                    + failure.getMessage());
        }
    }

    public LocalPackageDatabase.VerificationResult verify(String name, String version, String channel) {
        Optional<PackageEntry> entry = database.getPackage(name, version, channel);
        if (entry.isEmpty()) {
            return new LocalPackageDatabase.VerificationResult(name, false,
                    List.of(new LocalPackageDatabase.VerificationIssue("", "NOT_INSTALLED", "Package not found")));
        }
        return database.verifyPackage(entry.get(), installRoot);
    }

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
            PackageEntry mainPackage = entries.get(0);
            List<PackageEntry> additional = entries.size() > 1 ? entries.subList(1, entries.size()) : List.of();
            return new PackageManifest(mainPackage, additional, entries);
        }
    }

    private Map<String, FileEntry> extractPackageFilesToTemp(Path zipPath, PackageEntry pkgEntry,
                                                             Path tempDir) throws IOException {
        Map<String, FileEntry> extracted = new LinkedHashMap<>();
        long maxTotalExtractedSize = pkgEntry.files().values().stream()
                .mapToLong(FileEntry::size)
                .sum() + 10 * 1024 * 1024;
        long totalExtractedSize = 0;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            for (FileEntry fileEntry : pkgEntry.files().values()) {
                PackageLayout.validate(pkgEntry, fileEntry.path());
                ZipEntry zipEntry = zip.getEntry(fileEntry.path());
                if (zipEntry == null) {
                    zipEntry = zip.getEntry(pkgEntry.name() + "/" + fileEntry.path());
                }
                if (zipEntry == null) {
                    throw new IOException("File not found in ZIP: " + fileEntry.path());
                }

                Path destPath = PackageLayout.resolve(tempDir, fileEntry.path());
                Files.createDirectories(destPath.getParent());

                long fileSizeLimit = fileEntry.size() + 1024 * 1024;
                try (var is = zip.getInputStream(zipEntry);
                     var os = Files.newOutputStream(destPath, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long bytesWritten = 0;
                    while ((read = is.read(buffer)) > 0) {
                        bytesWritten += read;
                        if (bytesWritten > fileSizeLimit) {
                            throw new IOException("Extracted file exceeds size limit: " + fileEntry.path());
                        }
                        os.write(buffer, 0, read);
                    }
                }

                long actualSize = Files.size(destPath);
                totalExtractedSize += actualSize;
                if (totalExtractedSize > maxTotalExtractedSize) {
                    throw new IOException("Total extracted size exceeds limit: " + totalExtractedSize);
                }
                String actualSha256 = IoSupport.sha256(destPath);
                if (actualSize != fileEntry.size()) {
                    throw new IOException("Extracted file size mismatch: " + fileEntry.path()
                            + " expected " + fileEntry.size() + ", got " + actualSize);
                }
                if (!actualSha256.equalsIgnoreCase(fileEntry.sha256())) {
                    throw new IOException("Extracted file hash mismatch: " + fileEntry.path()
                            + " expected " + fileEntry.sha256() + ", got " + actualSha256);
                }
                extracted.put(fileEntry.path(), new FileEntry(fileEntry.path(), actualSize,
                        actualSha256, pkgEntry.name()));
            }
        }
        return extracted;
    }

    public static class InstallException extends Exception {
        public InstallException(String message) {
            super(message);
        }

        public InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record PackageManifest(PackageEntry mainPackage, List<PackageEntry> additionalPackages,
                                   List<PackageEntry> allPackages) {
    }
}
