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

import me.majhrs16.suite.api.ModuleDescriptor;
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
        List<ModuleChange> applied = new ArrayList<>();
        List<PackageEntry> oldPackages = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        for (PackageEntry entry : orderByDependencies(entries)) {
            if (entry.type() != PackageType.PACKAGE) {
                continue;
            }
            PackageEntry previous = before.get(entry.name());
            if (!PlatformCompatibility.matches(entry.platform(), moduleHost.platform().name())) {
                if (previous != null && packageHasActiveModule(previous, before)) {
                    return abortApply(applied, oldPackages, entries, before, entry,
                            changes, "New package " + entry.name() + " " + entry.version()
                                    + " is not compatible with " + moduleHost.platform().name(),
                            null, false, entry.name());
                }
                logger.info("Package " + entry.name() + " is for platform " + entry.platform()
                        + "; not activated on " + moduleHost.platform().name());
                continue;
            }

            List<ModuleArtifact> newArtifacts;
            try {
                newArtifacts = artifacts(entry, installed);
            } catch (IllegalStateException invalidPackage) {
                rollbackInstalledEntry(entry);
                return abortApply(applied, oldPackages, entries, before, entry,
                        changes, invalidPackage.getMessage(), invalidPackage,
                        false, entry.name());
            }

            if (previous != null && !previous.version().equals(entry.version())) {
                List<ModuleArtifact> oldArtifacts;
                try {
                    oldArtifacts = artifacts(previous, before);
                } catch (IllegalStateException invalidOldPackage) {
                    rollbackInstalledEntry(entry);
                    return abortApply(applied, oldPackages, entries, before, entry,
                            changes, invalidOldPackage.getMessage(), invalidOldPackage,
                            false, entry.name());
                }
                boolean active = oldArtifacts.stream()
                        .anyMatch(artifact -> moduleHost.activeModules().contains(artifact.moduleId()));
                if (active) {
                    PackageApply result = upgradePackage(oldArtifacts, newArtifacts,
                            installed, applied, seen);
                    changes.addAll(result.changes());
                    if (!result.ok()) {
                        return abortApply(applied, oldPackages, entries, before, entry,
                                changes, result.message(), result.cause(),
                                result.keepBoth(), result.failingModuleId());
                    }
                    oldPackages.add(previous);
                } else {
                    ActivationApply activation = activateArtifacts(newArtifacts, installed, seen);
                    changes.addAll(activation.changes());
                    applied.addAll(activation.applied());
                    if (!activation.ok()) {
                        return abortApply(applied, oldPackages, entries, before, entry,
                                changes, activation.message(), activation.cause(),
                                false, activation.failingModuleId());
                    }
                    oldPackages.add(previous);
                }
                continue;
            }

            ActivationApply activation = activateArtifacts(newArtifacts, installed, seen);
            changes.addAll(activation.changes());
            applied.addAll(activation.applied());
            if (!activation.ok()) {
                return abortApply(applied, oldPackages, entries, before, entry,
                        changes, activation.message(), activation.cause(),
                        false, activation.failingModuleId());
            }
        }

        commitRemovals(oldPackages, entries, before);
        return List.copyOf(changes);
    }

    private List<PackageChange> abortApply(
            List<ModuleChange> applied, List<PackageEntry> oldPackages,
            List<PackageEntry> newEntries, Map<String, PackageEntry> before,
            PackageEntry current, List<PackageChange> changes,
            String message, Throwable cause, boolean keepBoth,
            String failingModuleId) {
        String failedId = failingModuleId == null ? current.name() : failingModuleId;
        Compensation compensation = compensate(applied);
        if (keepBoth || !compensation.ok()) {
            String reason = message;
            if (!compensation.ok()) {
                reason = message + "; la compensación del runtime falló: "
                        + compensation.message() + "; ambas versiones se conservan y se requiere reinicio";
            } else if (keepBoth) {
                reason = message + "; se requiere reinicio y ambas versiones se conservan";
            }
            markRestartRequired(oldPackages, newEntries, applied, failedId, reason);
            List<PackageChange> result = compensatedChanges(applied, reason);
            result.add(PackageChange.failed(failedId, ModuleState.RESTART_REQUIRED,
                    reason, compensation.cause() != null ? compensation.cause() : cause));
            return List.copyOf(result);
        }

        rollbackNewPackages(newEntries, before);
        if (applied.isEmpty()) {
            if (changes.stream().noneMatch(change ->
                    change.moduleId().equals(failedId) && !change.isSuccess())) {
                changes.add(PackageChange.failed(failedId, stateFor(failedId), message, cause));
            }
            return List.copyOf(changes);
        }
        List<PackageChange> result = compensatedChanges(applied, message);
        result.add(PackageChange.failed(failedId, stateFor(failedId), message, cause));
        return List.copyOf(result);
    }

    private List<PackageChange> compensatedChanges(List<ModuleChange> applied, String message) {
        List<PackageChange> changes = new ArrayList<>();
        for (ModuleChange change : applied) {
            changes.add(new PackageChange(change.moduleId(), stateFor(change.moduleId()), false,
                    change.oldJar() != null, true,
                    "upgrade compensado tras el fallo del lote: " + message, null));
        }
        return changes;
    }

    private void commitRemovals(List<PackageEntry> oldPackages, List<PackageEntry> entries,
                                Map<String, PackageEntry> before) {
        for (PackageEntry old : oldPackages) {
            removeOldPackage(old);
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
        Map<String, PackageEntry> oldPackagesById = new LinkedHashMap<>(installed);
        for (PackageEntry old : oldEntries) {
            oldPackagesById.put(old.name(), old);
        }

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

        List<ModuleChange> applied = new ArrayList<>();
        List<PackageEntry> upgradedPackages = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (PackageEntry old : oldEntries) {
            PackageEntry next = findNew(old, newEntries);
            if (next == null || next.type() != PackageType.PACKAGE) {
                continue;
            }
            if (!PlatformCompatibility.matches(next.platform(), moduleHost.platform().name())) {
                if (packageHasActiveModule(old, oldPackagesById)) {
                    return failAndCompensate(applied, upgradedPackages, newEntries, installed,
                            "la nueva versión de " + old.name() + " no es compatible con "
                                    + moduleHost.platform().name(), null, false, old.name());
                }
                continue;
            }

            List<ModuleArtifact> oldArtifacts;
            List<ModuleArtifact> newArtifacts;
            try {
                oldArtifacts = artifacts(old, oldPackagesById);
                newArtifacts = artifacts(next, installed);
            } catch (IllegalStateException invalidPackage) {
                return failAndCompensate(applied, upgradedPackages, newEntries, installed,
                        invalidPackage.getMessage(), invalidPackage, false, old.name());
            }

            boolean active = oldArtifacts.stream()
                    .anyMatch(artifact -> moduleHost.activeModules().contains(artifact.moduleId()));
            if (active) {
                PackageApply result = upgradePackage(oldArtifacts, newArtifacts,
                        installed, applied, seen);
                if (!result.ok()) {
                    return failAndCompensate(applied, upgradedPackages, newEntries, installed,
                            result.message(), result.cause(), result.keepBoth(),
                            result.failingModuleId());
                }
                upgradedPackages.add(old);
            } else {
                ActivationApply activation = activateArtifacts(newArtifacts, installed, seen);
                applied.addAll(activation.applied());
                if (!activation.ok()) {
                    return failAndCompensate(applied, upgradedPackages, newEntries, installed,
                            activation.message(), activation.cause(), false,
                            activation.failingModuleId());
                }
                upgradedPackages.add(old);
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

    private PackageApply upgradePackage(List<ModuleArtifact> oldArtifacts,
                                        List<ModuleArtifact> newArtifacts,
                                        Map<String, PackageEntry> installed,
                                        List<ModuleChange> applied,
                                        Set<Path> seen) {
        Map<String, ModuleArtifact> newById = byModuleId(newArtifacts);
        List<PackageChange> changes = new ArrayList<>();
        Set<String> active = Set.copyOf(moduleHost.activeModules());

        for (ModuleArtifact old : oldArtifacts) {
            if (!active.contains(old.moduleId())) {
                continue;
            }
            ModuleArtifact next = newById.get(old.moduleId());
            if (next == null) {
                DeactivationResult deactivation = moduleHost.deactivate(old.moduleId());
                changes.add(removed(old.moduleId(), deactivation));
                if (!deactivation.isClean()) {
                    return PackageApply.keepBoth(changes, old.moduleId(),
                            "No se pudo retirar el módulo " + old.moduleId()
                                    + " del paquete: " + deactivation.message(),
                            deactivation.cause());
                }
                applied.add(new ModuleChange(old.moduleId(), old.jar(),
                        old.dependencies(), null, null, true));
                continue;
            }
            UpgradeResult upgrade = moduleHost.upgrade(next.moduleId(), next.jar(),
                    next.dependencies());
            changes.add(PackageChange.from(upgrade));
            if (!upgrade.isSuccess()) {
                return PackageApply.failed(changes, next.moduleId(),
                        upgrade.message(), upgrade.cause());
            }
            applied.add(new ModuleChange(next.moduleId(), old.jar(), old.dependencies(),
                    next.jar(), next.dependencies(), true));
        }

        List<ModuleArtifact> toActivate = newArtifacts.stream()
                .filter(artifact -> !active.contains(artifact.moduleId()))
                .toList();
        ActivationApply activation = activateArtifacts(toActivate, installed, seen);
        changes.addAll(activation.changes());
        applied.addAll(activation.applied());
        if (!activation.ok()) {
            return PackageApply.failed(changes, activation.failingModuleId(),
                    activation.message(), activation.cause());
        }
        return PackageApply.ok(changes);
    }

    private PackageUpgradeListener.Decision failAndCompensate(
            List<ModuleChange> applied, List<PackageEntry> oldPackages,
            List<PackageEntry> newEntries, Map<String, PackageEntry> installed,
            String message, Throwable cause, boolean keepBoth, String failingModuleId) {
        Compensation compensation = compensate(applied);
        if (compensation.ok() && !keepBoth) {
            return PackageUpgradeListener.Decision.rollback(message, cause);
        }
        String reason = message;
        if (!compensation.ok()) {
            reason = message + "; la compensación del runtime falló: "
                    + compensation.message() + "; ambas versiones se conservan y se requiere reinicio";
        } else if (keepBoth) {
            reason = message + "; se requiere reinicio y ambas versiones se conservan";
        }
        markRestartRequired(oldPackages, newEntries, applied, failingModuleId, reason);
        return PackageUpgradeListener.Decision.keepBoth(reason,
                compensation.cause() != null ? compensation.cause() : cause);
    }

    private Compensation compensate(List<ModuleChange> applied) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            ModuleChange change = applied.get(i);
            if (change.wasActive()) {
                if (change.oldJar() == null) {
                    return Compensation.failed("no se guardó el JAR anterior de "
                            + change.moduleId(), null);
                }
                if (moduleHost.activeModules().contains(change.moduleId())) {
                    UpgradeResult restored = moduleHost.upgrade(change.moduleId(),
                            change.oldJar(), change.oldDependencies());
                    if (!restored.isSuccess()) {
                        return Compensation.failed("no se pudo restaurar "
                                + change.moduleId() + ": " + restored.message(),
                                restored.cause());
                    }
                } else {
                    ActivationResult restored = moduleHost.activate(change.oldJar(),
                            change.oldDependencies());
                    if (!restored.isSuccess()) {
                        return Compensation.failed("no se pudo reactivar "
                                + change.moduleId() + ": " + restored.message(),
                                restored.cause());
                    }
                }
                if (moduleHost.state(change.moduleId())
                        .filter(state -> state == ModuleState.ACTIVE).isEmpty()) {
                    return Compensation.failed("la versión anterior de "
                            + change.moduleId() + " no quedó activa", null);
                }
            } else if (change.newJar() != null
                    && moduleHost.activeModules().contains(change.moduleId())) {
                DeactivationResult deactivation = moduleHost.deactivate(change.moduleId());
                if (!deactivation.isClean()) {
                    return Compensation.failed("no se pudo desactivar la versión nueva de "
                            + change.moduleId() + ": " + deactivation.message(),
                            deactivation.cause());
                }
            }
        }
        return Compensation.clean();
    }

    private void markRestartRequired(List<PackageEntry> oldPackages,
                                     List<PackageEntry> newEntries,
                                     List<ModuleChange> applied,
                                     String failingModuleId, String reason) {
        Set<String> moduleIds = new LinkedHashSet<>();
        for (ModuleChange change : applied) {
            moduleIds.add(change.moduleId());
        }
        if (failingModuleId != null) {
            moduleIds.add(failingModuleId);
        }
        collectModuleIds(oldPackages, moduleIds);
        collectModuleIds(newEntries, moduleIds);
        for (String moduleId : moduleIds) {
            moduleHost.requireRestart(moduleId, reason);
        }
    }

    private void collectModuleIds(List<PackageEntry> entries, Set<String> moduleIds) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());
        for (PackageEntry entry : entries) {
            if (entry.type() != PackageType.PACKAGE) {
                continue;
            }
            try {
                for (ModuleArtifact artifact : artifacts(entry, installed)) {
                    moduleIds.add(artifact.moduleId());
                }
            } catch (IllegalStateException invalid) {
                moduleIds.add(entry.name());
            }
        }
    }

    private boolean packageHasActiveModule(PackageEntry entry, Map<String, PackageEntry> installed) {
        try {
            for (ModuleArtifact artifact : artifacts(entry, installed)) {
                if (moduleHost.activeModules().contains(artifact.moduleId())) {
                    return true;
                }
            }
        } catch (IllegalStateException invalid) {
            return true;
        }
        return false;
    }

    private PackageChange removed(String moduleId, DeactivationResult deactivation) {
        return new PackageChange(moduleId,
                deactivation.isClean() ? ModuleState.INSTALLED : ModuleState.RESTART_REQUIRED,
                deactivation.isClean(), false, false,
                deactivation.isClean() ? "removed from upgraded package" : deactivation.message(),
                deactivation.cause());
    }

    private record ModuleArtifact(String moduleId, Path jar,
                                  List<Path> dependencies,
                                  ModuleDescriptor descriptor) {
    }

    private record ModuleChange(String moduleId,
                                Path oldJar, List<Path> oldDependencies,
                                Path newJar, List<Path> newDependencies,
                                boolean wasActive) {
    }

    private record ActivationApply(List<PackageChange> changes,
                                   List<ModuleChange> applied,
                                   boolean ok, String message, Throwable cause,
                                   String failingModuleId) {
        private static ActivationApply ok(List<PackageChange> changes,
                                          List<ModuleChange> applied) {
            return new ActivationApply(changes, applied, true, null, null, null);
        }

        private static ActivationApply failed(List<PackageChange> changes,
                                              List<ModuleChange> applied,
                                              String failingModuleId,
                                              String message, Throwable cause) {
            return new ActivationApply(changes, applied, false, message, cause, failingModuleId);
        }
    }

    private record PackageApply(List<PackageChange> changes, boolean ok, boolean keepBoth,
                                String message, Throwable cause, String failingModuleId) {
        private static PackageApply ok(List<PackageChange> changes) {
            return new PackageApply(changes, true, false, null, null, null);
        }

        private static PackageApply failed(List<PackageChange> changes, String failingModuleId,
                                           String message, Throwable cause) {
            return new PackageApply(changes, false, false, message, cause, failingModuleId);
        }

        private static PackageApply keepBoth(List<PackageChange> changes, String failingModuleId,
                                             String message, Throwable cause) {
            return new PackageApply(changes, false, true, message, cause, failingModuleId);
        }
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
            try {
                ActivationApply activation = activateArtifacts(artifacts(entry, installed),
                        installed, seen);
                for (PackageChange change : activation.changes()) {
                    results.add(new ActivationResult(change.moduleId(), change.state(),
                            change.message(), change.cause()));
                }
            } catch (IllegalStateException invalidPackage) {
                results.add(ActivationResult.failed(entry.name(),
                        invalidPackage.getMessage(), invalidPackage));
            }
        }
        return List.copyOf(results);
    }

    private ActivationApply activateArtifacts(List<ModuleArtifact> artifacts,
                                              Map<String, PackageEntry> installed,
                                              Set<Path> seen) {
        List<PackageChange> changes = new ArrayList<>();
        List<ModuleChange> applied = new ArrayList<>();
        for (ModuleArtifact artifact : orderArtifactsForActivation(artifacts)) {
            if (!seen.add(artifact.jar())) {
                continue;
            }
            ActivationResult result = moduleHost.activate(artifact.jar(), artifact.dependencies());
            changes.add(PackageChange.from(result));
            if (!result.isSuccess()) {
                return ActivationApply.failed(changes, applied, result.moduleId(),
                        result.message(), result.cause());
            }
            applied.add(new ModuleChange(result.moduleId(), null, List.of(),
                    artifact.jar(), artifact.dependencies(), false));
        }
        return ActivationApply.ok(changes, applied);
    }

    /**
     * Reads every module JAR of a package and maps it to the module id declared
     * by its own descriptor. A package may carry one or more module JARs; the
     * package name is never assumed to be the module id.
     */
    private List<ModuleArtifact> artifacts(PackageEntry entry,
                                           Map<String, PackageEntry> installed) {
        List<Path> jars = moduleJars(entry);
        if (jars.isEmpty()) {
            throw new IllegalStateException("Package " + entry.name() + " " + entry.version()
                    + " does not contain a module JAR");
        }
        List<Path> dependencies = dependencyJars(entry, installed);
        List<ModuleArtifact> artifacts = new ArrayList<>();
        Map<String, Path> byId = new LinkedHashMap<>();
        for (Path jar : jars) {
            ModuleDescriptor descriptor;
            try {
                descriptor = moduleHost.inspect(jar, dependencies);
            } catch (Throwable failure) {
                throw new IllegalStateException("Cannot read module JAR " + jar
                        + " in package " + entry.name() + ": " + failure.getMessage(), failure);
            }
            Path duplicate = byId.putIfAbsent(descriptor.name(), jar);
            if (duplicate != null) {
                throw new IllegalStateException("Package " + entry.name()
                        + " declares duplicate module id " + descriptor.name()
                        + " in " + duplicate + " and " + jar);
            }
            artifacts.add(new ModuleArtifact(descriptor.name(), jar, dependencies, descriptor));
        }
        return List.copyOf(artifacts);
    }

    private static Map<String, ModuleArtifact> byModuleId(List<ModuleArtifact> artifacts) {
        Map<String, ModuleArtifact> byId = new LinkedHashMap<>();
        for (ModuleArtifact artifact : artifacts) {
            byId.put(artifact.moduleId(), artifact);
        }
        return byId;
    }

    private static List<ModuleArtifact> orderArtifactsForActivation(
            List<ModuleArtifact> artifacts) {
        Map<String, ModuleArtifact> providers = new LinkedHashMap<>();
        for (ModuleArtifact artifact : artifacts) {
            for (var capability : artifact.descriptor().provides()) {
                providers.putIfAbsent(capability.name(), artifact);
            }
        }

        List<ModuleArtifact> ordered = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Set<String> pending = new LinkedHashSet<>();
        for (ModuleArtifact artifact : artifacts) {
            pending.add(artifact.moduleId());
        }
        Map<String, ModuleArtifact> byId = byModuleId(artifacts);
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (String moduleId : new ArrayList<>(pending)) {
                ModuleArtifact artifact = byId.get(moduleId);
                boolean ready = true;
                for (var requirement : artifact.descriptor().requires()) {
                    ModuleArtifact provider = providers.get(requirement.name());
                    if (provider != null && !provider.moduleId().equals(moduleId)
                            && !added.contains(provider.moduleId())) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    ordered.add(artifact);
                    added.add(moduleId);
                    pending.remove(moduleId);
                    progress = true;
                }
            }
        }
        for (String moduleId : pending) {
            ordered.add(byId.get(moduleId));
        }
        return ordered;
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

        for (String name : pending) {
            ordered.add(byName.get(name));
        }
        return ordered;
    }

    private void deactivateIfActive(String packageName) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());
        PackageEntry entry = installed.get(packageName);
        Set<String> moduleIds = new LinkedHashSet<>();
        if (entry != null && entry.type() == PackageType.PACKAGE) {
            try {
                for (ModuleArtifact artifact : artifacts(entry, installed)) {
                    moduleIds.add(artifact.moduleId());
                }
            } catch (IllegalStateException invalid) {
                logger.warn("Cannot inspect package " + packageName
                        + " before removal: " + invalid.getMessage());
            }
        }
        if (moduleIds.isEmpty() && moduleHost.activeModules().contains(packageName)) {
            moduleIds.add(packageName);
        }
        for (String moduleId : moduleIds) {
            if (moduleHost.activeModules().contains(moduleId)) {
                DeactivationResult result = moduleHost.deactivate(moduleId);
                if (!result.isClean()) {
                    logger.warn("Module " + moduleId
                            + " did not deactivate cleanly before removal: " + result.message());
                }
            } else if (moduleHost.state(moduleId)
                    .filter(ModuleState.RESTART_REQUIRED::equals).isPresent()) {
                logger.warn("Module " + moduleId
                        + " already required a restart; removing its files now");
            }
        }
    }

    private boolean anyActiveModuleDependsOn(String dependencyName) {
        Map<String, PackageEntry> latest = latestByName(manager.packageList());
        for (String active : moduleHost.activeModules()) {
            PackageEntry entry = packageForModule(active, latest);
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

    private PackageEntry packageForModule(String moduleId, Map<String, PackageEntry> latest) {
        PackageEntry direct = latest.get(moduleId);
        if (direct != null) {
            return direct;
        }
        for (PackageEntry candidate : latest.values()) {
            if (candidate.type() != PackageType.PACKAGE) {
                continue;
            }
            try {
                for (ModuleArtifact artifact : artifacts(candidate, latest)) {
                    if (artifact.moduleId().equals(moduleId)) {
                        return candidate;
                    }
                }
            } catch (IllegalStateException invalid) {
                logger.warn("Cannot inspect package " + candidate.name()
                        + " while resolving module " + moduleId + ": " + invalid.getMessage());
            }
        }
        return null;
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
