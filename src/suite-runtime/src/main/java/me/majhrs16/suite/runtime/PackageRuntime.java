package me.majhrs16.suite.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.AptInstaller;
import me.majhrs16.suite.manager.apt.ManagerFacade;
import me.majhrs16.suite.manager.apt.PackageUpgradeListener;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.spi.PlatformCompatibility;
import me.majhrs16.suite.manager.spi.Version;

/**
 * Connects the package manager with the module runtime.
 *
 * <p>Installing a package copies its artifacts to {@code library/packages} and
 * then activates the module JARs it contains. Removing a package deactivates
 * the module before deleting its files. The package database
 * ({@code library/status}) keeps recording <em>installation</em>; activation
 * results are reported by {@link ModuleHost} and, when configured, persisted by
 * a {@link RuntimeStateStore} in the manager's runtime directory.</p>
 *
 * <p>This class is the bridge mandated by ADR-019: the manager owns package
 * facts and the runtime owns live module state. Neither pretends to be the
 * other. A package upgrade is committed only after the new module version is
 * active; otherwise the new files are rolled back and the previous version
 * stays installed.</p>
 */
public final class PackageRuntime {

    private final ManagerFacade manager;
    private final ModuleHost moduleHost;
    private final PluginLogger logger;
    private final Path installRoot;
    private final Path packagesRoot;
    private final Path dependenciesRoot;

    /**
     * @param manager    initialized package manager
     * @param moduleHost module runtime
     * @param logger     logger for policy warnings
     */
    public PackageRuntime(ManagerFacade manager, ModuleHost moduleHost, PluginLogger logger) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.moduleHost = Objects.requireNonNull(moduleHost, "moduleHost");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.installRoot = manager.getManagerDir().getParent().toAbsolutePath().normalize();
        this.packagesRoot = moduleHost.packagesDir().toAbsolutePath().normalize();
        this.dependenciesRoot = packagesRoot.getParent().resolve("dependencies");
    }

    /**
     * Installs a local distribution ZIP and activates the module JARs it
     * installs. When the ZIP contains a new version of an active package, the
     * module is hot-upgraded and the previous package is removed only after the
     * new version starts.
     *
     * @param zipPath distribution ZIP
     * @param force   pass-through to the package installer
     * @return one change per module JAR found in the installed packages
     * @throws PackageInstaller.InstallException if installation fails
     */
    public List<PackageChange> installLocal(Path zipPath, boolean force)
            throws PackageInstaller.InstallException {
        Map<String, PackageEntry> before = latestByName(manager.packageList());
        List<PackageEntry> installed = manager.packageInstall(zipPath, force);
        return applyInstalled(installed, before);
    }

    /**
     * Installs packages from configured repositories and activates the newly
     * installed modules.
     *
     * <p>When the resolver asks for confirmation, nothing is activated: the
     * caller must confirm first.</p>
     *
     * @param packages package names
     * @param version  version constraint
     * @param channel  release channel
     * @param force    force conflicting packages
     * @return the manager result
     */
    public AptInstaller.InstallResult installFromRepositories(Collection<String> packages,
                                                              String version,
                                                              String channel,
                                                              boolean force) {
        Map<String, PackageEntry> before = latestByName(manager.packageList());
        AptInstaller.InstallResult result = manager.repoInstall(packages, version, channel, force);
        if (result.success() && !result.requiresConfirmation()) {
            List<PackageEntry> fresh = new ArrayList<>();
            for (PackageEntry entry : manager.packageList()) {
                if (!before.containsKey(entry.name())
                        || !before.get(entry.name()).version().equals(entry.version())) {
                    fresh.add(entry);
                }
            }
            List<PackageChange> changes = applyInstalled(fresh, before);
            Optional<PackageChange> failed = changes.stream()
                    .filter(change -> !change.isSuccess())
                    .findFirst();
            if (failed.isPresent()) {
                PackageChange change = failed.get();
                return new AptInstaller.InstallResult(false, result.packages(),
                        "Installed but activation failed for " + change.moduleId()
                                + ": " + change.message());
            }
        }
        return result;
    }

    /**
     * Upgrades every installed package. The runtime listener activates each new
     * version before the manager removes the old files.
     *
     * @return the manager result
     */
    public AptInstaller.InstallResult upgrade() {
        return manager.repoUpgrade(this::beforeRemovingOld);
    }

    /**
     * Deactivates a module and removes its package from the local database.
     *
     * @param name         package name
     * @param version      exact version or {@code any}
     * @param channel      release channel
     * @param autoRemove   also remove unused dependencies
     * @throws PackageInstaller.InstallException if removal fails
     */
    public void removeLocal(String name, String version, String channel, boolean autoRemove)
            throws PackageInstaller.InstallException {
        deactivateIfActive(name);
        manager.packageRemove(name, version, channel, autoRemove);
    }

    /**
     * Deactivates a module and removes its package through the repository
     * manager.
     *
     * @param name       package name
     * @param version    exact version or {@code any}
     * @param channel    release channel
     * @param autoRemove also remove unused dependencies
     * @return the manager result
     */
    public AptInstaller.RemoveResult removeFromRepositories(String name, String version,
                                                            String channel, boolean autoRemove) {
        deactivateIfActive(name);
        return manager.repoRemove(name, version, channel, autoRemove);
    }

    /**
     * Activates the module JARs declared by the given installed packages.
     *
     * <p>Only files under {@code library/packages} are eligible. A package that
     * declares a JAR outside the module store is reported and skipped instead of
     * being silently ignored.</p>
     *
     * @param entries installed package entries
     * @return activation result per eligible JAR
     */
    public List<ActivationResult> activate(List<PackageEntry> entries) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());
        for (PackageEntry entry : entries) {
            mergeLatest(installed, entry);
        }
        return activateEntries(entries, installed, new HashSet<>());
    }

    private List<PackageChange> applyInstalled(List<PackageEntry> entries,
                                               Map<String, PackageEntry> before) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());
        for (PackageEntry entry : entries) {
            mergeLatest(installed, entry);
        }

        List<PackageChange> changes = new ArrayList<>();
        List<AppliedChange> applied = new ArrayList<>();
        List<PackageEntry> oldEntries = new ArrayList<>();
        for (PackageEntry entry : entries) {
            PackageEntry previous = before.get(entry.name());
            if (previous != null && !previous.version().equals(entry.version())) {
                oldEntries.add(previous);
            }
        }

        Set<Path> seen = new HashSet<>();
        for (PackageEntry entry : orderByDependencies(entries)) {
            if (entry.type() != PackageType.PACKAGE) {
                continue;
            }
            PackageEntry previous = before.get(entry.name());
            boolean active = moduleHost.activeModules().contains(entry.name());
            if (!PlatformCompatibility.matches(entry.platform(), moduleHost.platform().name())) {
                if (active) {
                    return abortApply(applied, oldEntries, entries, installed, before,
                            entry, List.of(), changes,
                            "New package " + entry.name() + " " + entry.version()
                                    + " is not compatible with " + moduleHost.platform().name(),
                            null);
                }
                logger.info("Package " + entry.name() + " is for platform " + entry.platform()
                        + "; not activated on " + moduleHost.platform().name());
                continue;
            }

            Optional<Path> moduleJar;
            try {
                moduleJar = singleModuleJar(entry);
            } catch (IllegalStateException invalidPackage) {
                rollbackInstalledEntry(entry);
                return abortApply(applied, oldEntries, entries, installed, before,
                        entry, List.of(), changes,
                        invalidPackage.getMessage(), invalidPackage);
            }
            if (moduleJar.isEmpty()) {
                rollbackInstalledEntry(entry);
                return abortApply(applied, oldEntries, entries, installed, before,
                        entry, List.of(), changes,
                        "Package " + entry.name() + " " + entry.version()
                                + " does not contain a module JAR", null);
            }

            if (previous != null && !previous.version().equals(entry.version()) && active) {
                UpgradeResult upgrade = moduleHost.upgrade(
                        entry.name(), moduleJar.get(), dependencyJars(entry, installed));
                changes.add(PackageChange.from(upgrade));
                if (!upgrade.isSuccess()) {
                    return abortApply(applied, oldEntries, entries, installed, before,
                            entry, List.of(), changes, upgrade.message(), upgrade.cause());
                }
                applied.add(new AppliedChange(previous, entry, true, List.of()));
                continue;
            }

            List<ActivationResult> results = activateEntries(
                    List.of(entry), installed, seen);
            for (ActivationResult result : results) {
                changes.add(PackageChange.from(result));
            }
            Optional<ActivationResult> failure = results.stream()
                    .filter(result -> !result.isSuccess())
                    .findFirst();
            if (failure.isPresent()) {
                return abortApply(applied, oldEntries, entries, installed, before,
                        entry, results, changes,
                        failure.get().message(), failure.get().cause());
            }
            if (!results.isEmpty()) {
                applied.add(new AppliedChange(previous, entry, false, results));
            }
        }

        commitRemovals(applied, entries, before);
        return List.copyOf(changes);
    }

    private List<PackageChange> abortApply(
            List<AppliedChange> applied, List<PackageEntry> oldEntries,
            List<PackageEntry> newEntries, Map<String, PackageEntry> installed,
            Map<String, PackageEntry> before, PackageEntry current,
            List<ActivationResult> currentResults, List<PackageChange> changes,
            String message, Throwable cause) {
        Compensation currentCleanup = deactivateActivated(currentResults);
        if (!currentCleanup.ok()) {
            String fullMessage = message + "; no se pudo limpiar la versión nueva: "
                    + currentCleanup.message() + "; se requiere reinicio";
            markRestartRequired(oldEntries, newEntries, fullMessage);
            changes.add(PackageChange.failed(current.name(), ModuleState.RESTART_REQUIRED,
                    fullMessage, currentCleanup.cause()));
            return List.copyOf(changes);
        }

        if (applied.isEmpty()) {
            rollbackNewPackages(newEntries, before);
            if (changes.stream().noneMatch(change ->
                    change.moduleId().equals(current.name()) && !change.isSuccess())) {
                changes.add(PackageChange.failed(current.name(), stateFor(current.name()),
                        message, cause));
            }
            return List.copyOf(changes);
        }

        Compensation compensation = compensate(applied, oldEntries, installed);
        if (!compensation.ok()) {
            String fullMessage = message + "; la compensación del runtime falló: "
                    + compensation.message() + "; ambas versiones se conservan y se requiere reinicio";
            markRestartRequired(oldEntries, newEntries, fullMessage);
            List<PackageChange> result = compensatedChanges(applied, fullMessage);
            result.add(PackageChange.failed(current.name(), ModuleState.RESTART_REQUIRED,
                    fullMessage, compensation.cause() != null ? compensation.cause() : cause));
            return List.copyOf(result);
        }

        rollbackNewPackages(newEntries, before);
        List<PackageChange> result = compensatedChanges(applied, message);
        result.add(PackageChange.failed(current.name(), stateFor(current.name()), message, cause));
        return List.copyOf(result);
    }

    private List<PackageChange> compensatedChanges(List<AppliedChange> applied, String message) {
        List<PackageChange> changes = new ArrayList<>();
        for (AppliedChange change : applied) {
            ModuleState state = change.wasActive()
                    ? ModuleState.ACTIVE
                    : ModuleState.INSTALLED;
            changes.add(new PackageChange(change.newEntry().name(), state, false,
                    change.oldEntry() != null, true,
                    "upgrade compensado tras el fallo del lote: " + message, null));
        }
        return changes;
    }

    private void commitRemovals(List<AppliedChange> applied, List<PackageEntry> entries,
                                Map<String, PackageEntry> before) {
        for (AppliedChange change : applied) {
            if (change.oldEntry() != null) {
                removeOldPackage(change.oldEntry());
            }
        }
        for (PackageEntry entry : entries) {
            if (entry.type() != PackageType.DEPENDENCY) {
                continue;
            }
            PackageEntry previous = before.get(entry.name());
            if (previous != null && !previous.version().equals(entry.version())
                    && !anyActiveModuleDependsOn(entry.name())) {
                removeOldPackage(previous);
            }
        }
    }

    private void rollbackNewPackages(List<PackageEntry> newEntries,
                                     Map<String, PackageEntry> before) {
        for (int i = newEntries.size() - 1; i >= 0; i--) {
            PackageEntry entry = newEntries.get(i);
            PackageEntry previous = before.get(entry.name());
            // A failed first install stays in library/status (F3 contract); only
            // the new side of an upgrade is removed when the batch rolls back.
            if (previous == null || previous.version().equals(entry.version())) {
                continue;
            }
            rollbackInstalledEntry(entry);
        }
    }

    private ModuleState stateFor(String moduleId) {
        return moduleHost.state(moduleId).orElse(ModuleState.FAILED);
    }

    PackageUpgradeListener.Decision beforeRemovingOld(List<PackageEntry> oldEntries,
                                                       List<PackageEntry> newEntries) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());

        // Reject unsafe dependency upgrades before touching any active module:
        // a rollback after a module already upgraded would leave the runtime
        // pointing at files the manager is about to delete.
        for (PackageEntry old : oldEntries) {
            PackageEntry next = findNew(old, newEntries);
            if (next != null && next.type() == PackageType.DEPENDENCY
                    && anyActiveModuleDependsOn(old.name())) {
                return PackageUpgradeListener.Decision.rollback(
                        "la dependencia compartida " + old.name()
                                + " está en uso; reinicia antes de actualizarla", null);
            }
        }

        List<AppliedChange> applied = new ArrayList<>();
        for (PackageEntry old : oldEntries) {
            PackageEntry next = findNew(old, newEntries);
            if (next == null || next.type() != PackageType.PACKAGE) {
                continue;
            }
            boolean active = moduleHost.activeModules().contains(old.name());
            if (!PlatformCompatibility.matches(next.platform(), moduleHost.platform().name())) {
                if (active) {
                    return failAndCompensate(applied, oldEntries, newEntries, installed,
                            "la nueva versión de " + old.name() + " no es compatible con "
                                    + moduleHost.platform().name(), null);
                }
                continue;
            }

            Optional<Path> moduleJar;
            try {
                moduleJar = singleModuleJar(next);
            } catch (IllegalStateException invalidPackage) {
                return failAndCompensate(applied, oldEntries, newEntries, installed,
                        invalidPackage.getMessage(), invalidPackage);
            }
            if (moduleJar.isEmpty()) {
                return failAndCompensate(applied, oldEntries, newEntries, installed,
                        "la nueva versión de " + old.name()
                                + " no contiene un JAR de módulo", null);
            }
            if (active) {
                UpgradeResult upgrade = moduleHost.upgrade(
                        old.name(), moduleJar.get(), dependencyJars(next, installed));
                if (!upgrade.isSuccess()) {
                    return failAndCompensate(applied, oldEntries, newEntries, installed,
                            upgrade.message(), upgrade.cause());
                }
                applied.add(new AppliedChange(old, next, true, List.of()));
            } else {
                List<ActivationResult> results = activateEntries(
                        List.of(next), installed, new HashSet<>());
                Optional<ActivationResult> failure = results.stream()
                        .filter(result -> !result.isSuccess())
                        .findFirst();
                if (failure.isPresent()) {
                    Compensation current = deactivateActivated(results);
                    if (!current.ok()) {
                        String message = "la activación de " + next.name()
                                + " falló y su limpieza también: " + current.message();
                        markRestartRequired(oldEntries, newEntries, message);
                        return PackageUpgradeListener.Decision.keepBoth(message, current.cause());
                    }
                    return failAndCompensate(applied, oldEntries, newEntries, installed,
                            failure.get().message(), failure.get().cause());
                }
                applied.add(new AppliedChange(old, next, false, results));
            }
        }
        return PackageUpgradeListener.Decision.allow();
    }

    private static PackageEntry findNew(PackageEntry old, List<PackageEntry> newEntries) {
        return newEntries.stream()
                .filter(entry -> entry.name().equals(old.name()))
                .findFirst()
                .orElse(null);
    }

    private PackageUpgradeListener.Decision failAndCompensate(
            List<AppliedChange> applied, List<PackageEntry> oldEntries,
            List<PackageEntry> newEntries, Map<String, PackageEntry> installed,
            String message, Throwable cause) {
        Compensation compensation = compensate(applied, oldEntries, installed);
        if (compensation.ok()) {
            return PackageUpgradeListener.Decision.rollback(message, cause);
        }
        String fullMessage = message + "; la compensación del runtime falló: "
                + compensation.message() + "; ambas versiones se conservan y se requiere reinicio";
        markRestartRequired(oldEntries, newEntries, fullMessage);
        return PackageUpgradeListener.Decision.keepBoth(fullMessage,
                compensation.cause() != null ? compensation.cause() : cause);
    }

    private Compensation compensate(List<AppliedChange> applied,
                                    List<PackageEntry> oldEntries,
                                    Map<String, PackageEntry> installed) {
        if (applied.isEmpty()) {
            return Compensation.clean();
        }
        Map<String, PackageEntry> rollbackInstalled = new LinkedHashMap<>(installed);
        for (PackageEntry old : oldEntries) {
            rollbackInstalled.put(old.name(), old);
        }

        for (int i = applied.size() - 1; i >= 0; i--) {
            AppliedChange change = applied.get(i);
            if (change.wasActive()) {
                Optional<Path> oldJar;
                try {
                    oldJar = singleModuleJar(change.oldEntry());
                } catch (IllegalStateException invalidOldPackage) {
                    return Compensation.failed(invalidOldPackage.getMessage(), invalidOldPackage);
                }
                if (oldJar.isEmpty()) {
                    return Compensation.failed("no se encontró el JAR anterior de "
                            + change.oldEntry().name(), null);
                }
                UpgradeResult restored = moduleHost.upgrade(change.oldEntry().name(),
                        oldJar.get(), dependencyJars(change.oldEntry(), rollbackInstalled));
                if (!restored.isSuccess()) {
                    return Compensation.failed("no se pudo restaurar "
                            + change.oldEntry().name() + " " + change.oldEntry().version()
                            + ": " + restored.message(), restored.cause());
                }
                if (moduleHost.state(change.oldEntry().name())
                        .filter(state -> state == ModuleState.ACTIVE).isEmpty()) {
                    return Compensation.failed("la versión anterior de "
                            + change.oldEntry().name() + " no quedó activa", null);
                }
            } else {
                Compensation deactivation = deactivateActivated(change.activationResults());
                if (!deactivation.ok()) {
                    return deactivation;
                }
            }
        }
        return Compensation.clean();
    }

    private Compensation deactivateActivated(List<ActivationResult> results) {
        Throwable failure = null;
        for (ActivationResult result : results) {
            if (!result.isSuccess()) {
                continue;
            }
            DeactivationResult deactivation = moduleHost.deactivate(result.moduleId());
            if (moduleHost.activeModules().contains(result.moduleId())) {
                failure = first(failure, deactivation.cause() != null
                        ? deactivation.cause()
                        : new IllegalStateException("module is still active: " + result.moduleId()));
            } else if (deactivation.state() == ModuleState.RESTART_REQUIRED) {
                failure = first(failure, deactivation.cause() != null
                        ? deactivation.cause()
                        : new IllegalStateException("module did not release cleanly: "
                                + result.moduleId()));
            }
        }
        return failure == null
                ? Compensation.clean()
                : Compensation.failed("no se pudo desactivar la versión nueva", failure);
    }

    private void markRestartRequired(List<PackageEntry> oldEntries,
                                     List<PackageEntry> newEntries, String reason) {
        Set<String> moduleIds = new LinkedHashSet<>();
        for (PackageEntry entry : oldEntries) {
            if (entry.type() != PackageType.DEPENDENCY) {
                moduleIds.add(entry.name());
            }
        }
        for (PackageEntry entry : newEntries) {
            if (entry.type() != PackageType.DEPENDENCY) {
                moduleIds.add(entry.name());
            }
        }
        for (String moduleId : moduleIds) {
            moduleHost.requireRestart(moduleId, reason);
        }
    }

    private static Throwable first(Throwable current, Throwable candidate) {
        return current == null ? candidate : current;
    }

    private record AppliedChange(PackageEntry oldEntry, PackageEntry newEntry,
                                 boolean wasActive, List<ActivationResult> activationResults) {
    }

    private record Compensation(boolean ok, String message, Throwable cause) {

        private static Compensation clean() {
            return new Compensation(true, null, null);
        }

        private static Compensation failed(String message, Throwable cause) {
            return new Compensation(false, message, cause);
        }
    }

    private List<ActivationResult> activateEntries(List<PackageEntry> entries,
                                                   Map<String, PackageEntry> installed,
                                                   Set<Path> seen) {
        List<ActivationResult> results = new ArrayList<>();
        for (PackageEntry entry : orderByDependencies(latestEntries(entries))) {
            if (entry.type() != PackageType.PACKAGE) {
                continue;
            }
            if (!PlatformCompatibility.matches(entry.platform(), moduleHost.platform().name())) {
                logger.info("Package " + entry.name() + " is for platform " + entry.platform()
                        + "; not activated on " + moduleHost.platform().name());
                continue;
            }
            Optional<Path> moduleJar;
            try {
                moduleJar = singleModuleJar(entry);
            } catch (IllegalStateException invalidPackage) {
                results.add(ActivationResult.failed(entry.name(),
                        invalidPackage.getMessage(), invalidPackage));
                continue;
            }
            if (moduleJar.isEmpty()) {
                results.add(ActivationResult.failed(entry.name(),
                        "Package " + entry.name() + " " + entry.version()
                                + " does not contain a module JAR", null));
                continue;
            }
            if (seen.add(moduleJar.get())) {
                results.add(moduleHost.activate(
                        moduleJar.get(), dependencyJars(entry, installed)));
            }
        }
        return List.copyOf(results);
    }

    /**
     * A module package carries exactly one executable JAR. Additional
     * libraries belong in a {@code DEPENDENCY} package; a package with two
     * module JARs is ambiguous for upgrades and is rejected instead of silently
     * activating or upgrading only the first one.
     */
    private Optional<Path> singleModuleJar(PackageEntry entry) {
        if (entry.type() != PackageType.PACKAGE) {
            return Optional.empty();
        }
        List<Path> jars = moduleJars(entry);
        if (jars.size() > 1) {
            throw new IllegalStateException("Package " + entry.name() + " " + entry.version()
                    + " declares " + jars.size()
                    + " module JARs; exactly one is required");
        }
        return jars.stream().findFirst();
    }

    private List<Path> moduleJars(PackageEntry entry) {
        List<Path> jars = new ArrayList<>();
        for (FileEntry file : entry.files().values()) {
            if (!isJar(file.path())) {
                continue;
            }
            Path jar = installRoot.resolve(file.path()).normalize();
            if (!jar.startsWith(packagesRoot)) {
                throw new IllegalStateException("Package " + entry.name()
                        + " declares a JAR outside library/packages: " + file.path());
            }
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("Installed module JAR is missing: " + jar);
            }
            jars.add(jar);
        }
        return jars;
    }

    private List<Path> dependencyJars(PackageEntry entry, Map<String, PackageEntry> installed) {
        List<Path> jars = new ArrayList<>();
        collectDependencyJars(entry, installed, jars, new HashSet<>());
        return List.copyOf(jars);
    }

    private void collectDependencyJars(PackageEntry entry, Map<String, PackageEntry> installed,
                                       List<Path> jars, Set<String> visited) {
        for (String dependency : entry.dependencies()) {
            ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(dependency);
            if (spec == null || !visited.add(spec.name())) {
                continue;
            }
            PackageEntry dependencyEntry = installed.get(spec.name());
            if (dependencyEntry == null) {
                logger.warn("Dependency " + spec.name() + " of " + entry.name()
                        + " is not installed; its JARs are not visible to the module");
                continue;
            }
            if (dependencyEntry.type() == PackageType.DEPENDENCY) {
                for (FileEntry file : dependencyEntry.files().values()) {
                    if (!isJar(file.path())) {
                        continue;
                    }
                    Path jar = installRoot.resolve(file.path()).normalize();
                    if (!jar.startsWith(dependenciesRoot)) {
                        logger.warn("Dependency package " + dependencyEntry.name()
                                + " installs a JAR outside library/dependencies: "
                                + file.path() + "; not added to the module classloader");
                        continue;
                    }
                    if (Files.isRegularFile(jar)) {
                        jars.add(jar);
                    } else {
                        logger.warn("Dependency JAR is missing: " + jar);
                    }
                }
            }
            collectDependencyJars(dependencyEntry, installed, jars, visited);
        }
    }

    private List<PackageEntry> orderByDependencies(List<PackageEntry> entries) {
        Map<String, PackageEntry> byName = new LinkedHashMap<>();
        for (PackageEntry entry : entries) {
            byName.putIfAbsent(entry.name(), entry);
        }

        List<PackageEntry> ordered = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Set<String> pending = new LinkedHashSet<>(byName.keySet());
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (String name : new ArrayList<>(pending)) {
                PackageEntry entry = byName.get(name);
                boolean ready = true;
                for (String dependency : entry.dependencies()) {
                    ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(dependency);
                    if (spec != null && byName.containsKey(spec.name()) && !added.contains(spec.name())) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    ordered.add(entry);
                    added.add(name);
                    pending.remove(name);
                    progress = true;
                }
            }
        }

        // A cycle cannot be ordered; activate the rest in manifest order so the
        // runtime reports the unsatisfied requirement instead of hanging.
        for (String name : pending) {
            ordered.add(byName.get(name));
        }
        return ordered;
    }

    private void deactivateIfActive(String moduleId) {
        if (moduleHost.activeModules().contains(moduleId)) {
            DeactivationResult result = moduleHost.deactivate(moduleId);
            if (!result.isClean()) {
                logger.warn("Module " + moduleId + " did not deactivate cleanly before removal: "
                        + result.message());
            }
        } else if (moduleHost.state(moduleId).filter(ModuleState.RESTART_REQUIRED::equals).isPresent()) {
            logger.warn("Module " + moduleId + " already required a restart; removing its files now");
        }
    }

    private boolean anyActiveModuleDependsOn(String dependencyName) {
        Map<String, PackageEntry> latest = latestByName(manager.packageList());
        for (String active : moduleHost.activeModules()) {
            PackageEntry entry = latest.get(active);
            if (entry == null) {
                // A module activated outside the package database may still use
                // the dependency; keeping the old JAR is the safe choice.
                return true;
            }
            if (dependsOn(entry, dependencyName, latest, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private static boolean dependsOn(PackageEntry entry, String dependencyName,
                                     Map<String, PackageEntry> latest, Set<String> visited) {
        if (!visited.add(entry.name())) {
            return false;
        }
        for (String dependency : entry.dependencies()) {
            ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(dependency);
            if (spec == null) {
                continue;
            }
            if (spec.name().equals(dependencyName)) {
                return true;
            }
            PackageEntry transitive = latest.get(spec.name());
            if (transitive != null && dependsOn(transitive, dependencyName, latest, visited)) {
                return true;
            }
        }
        return false;
    }

    private void removeOldPackage(PackageEntry old) {
        try {
            manager.packageRemove(old.name(), old.version(), old.channel(), false);
        } catch (PackageInstaller.InstallException failure) {
            logger.warn("New version is active but the previous package " + old.name()
                    + " " + old.version() + " could not be removed: " + failure.getMessage());
        }
    }

    private void rollbackInstalledEntry(PackageEntry entry) {
        try {
            manager.packageRemove(entry.name(), entry.version(), entry.channel(), false);
        } catch (PackageInstaller.InstallException failure) {
            logger.warn("Failed to roll back package " + entry.name() + " "
                    + entry.version() + ": " + failure.getMessage());
        }
    }

    private static List<PackageEntry> latestEntries(List<PackageEntry> entries) {
        return new ArrayList<>(latestByName(entries).values());
    }

    private static Map<String, PackageEntry> latestByName(List<PackageEntry> entries) {
        Map<String, PackageEntry> latest = new LinkedHashMap<>();
        for (PackageEntry entry : entries) {
            mergeLatest(latest, entry);
        }
        return latest;
    }

    private static void mergeLatest(Map<String, PackageEntry> latest, PackageEntry entry) {
        PackageEntry current = latest.get(entry.name());
        if (current == null || Version.parse(entry.version())
                .compareTo(Version.parse(current.version())) > 0) {
            latest.put(entry.name(), entry);
        }
    }

    private static boolean isJar(String path) {
        return path.length() > 4 && path.regionMatches(true, path.length() - 4, ".jar", 0, 4);
    }
}
