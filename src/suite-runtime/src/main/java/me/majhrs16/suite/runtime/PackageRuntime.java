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
 * <p>ADR-027 separates three stages:</p>
 * <ol>
 *   <li>download and verify every artifact;</li>
 *   <li>commit files and {@code library/status} in one durable transaction;</li>
 *   <li>activate the committed modules.</li>
 * </ol>
 *
 * <p>Only stage 2 has an automatic rollback. A stage-3 failure keeps the
 * installation committed, stops the modules started in this pass in reverse
 * order and reports an incomplete activation. Old package versions stay on disk
 * until an explicit cleanup confirms that no runtime reference remains.</p>
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
     * Installs a local distribution ZIP and activates the committed modules.
     *
     * @param zipPath distribution ZIP
     * @param force   pass-through to the package installer
     * @return one change per module JAR that was activated or failed
     * @throws PackageInstaller.InstallException if stage 2 fails
     */
    public List<PackageChange> installLocal(Path zipPath, boolean force)
            throws PackageInstaller.InstallException {
        Map<String, PackageEntry> before = latestByName(manager.packageList());
        List<PackageEntry> installed = manager.packageInstall(zipPath, force);
        return activateCommitted(installed, before);
    }

    /**
     * Installs packages from configured repositories and activates the newly
     * installed modules after the file/status commit.
     *
     * <p>When the resolver asks for confirmation, nothing is installed or
     * activated: the caller must confirm first.</p>
     *
     * @param packages package names
     * @param version  version constraint
     * @param channel  release channel
     * @param force    force conflicting packages
     * @return manager result including the activation outcome
     */
    public AptInstaller.InstallResult installFromRepositories(Collection<String> packages,
                                                              String version,
                                                              String channel,
                                                              boolean force) {
        Map<String, PackageEntry> before = latestByName(manager.packageList());
        List<Deactivated> deactivated = new ArrayList<>();
        Set<String> replacedPackages = new LinkedHashSet<>();
        AptInstaller.InstallResult result = manager.repoInstall(
                packages, version, channel, force, true, replacements -> {
                    for (PackageEntry replacement : replacements) {
                        replacedPackages.add(replacement.name());
                        deactivatePackage(replacement.name(), deactivated);
                    }
                });
        if (!result.success() || result.requiresConfirmation()) {
            if (!deactivated.isEmpty()) {
                reactivate(deactivated, new PackageInstaller.InstallException(
                        result.error() == null ? "package install failed" : result.error()),
                        replacedPackages);
            }
            return result;
        }
        for (String replaced : replacedPackages) {
            moduleHost.clearPackageState(replaced);
        }
        List<PackageChange> changes = activateCommitted(freshEntries(before), before);
        return withActivation(result, changes);
    }

    /**
     * Upgrades every installed package. The file commit happens first; the
     * runtime then replaces active modules and never vetoes the commit.
     *
     * @return manager result including the activation outcome
     */
    public AptInstaller.InstallResult upgrade() {
        Map<String, PackageEntry> before = latestByName(manager.packageList());
        AptInstaller.InstallResult result = manager.repoUpgrade();
        if (!result.success()) {
            return result;
        }
        List<PackageChange> changes = activateCommitted(freshEntries(before), before);
        return withActivation(result, changes);
    }

    /**
     * Deactivates a module and removes its package from the local database.
     *
     * <p>The manager invokes the barrier before any file or status mutation.
     * If the transaction fails after the barrier, the runtime attempts to
     * reactivate exactly the modules it stopped.</p>
     *
     * @param name         package name
     * @param version      exact version or {@code any}
     * @param channel      release channel
     * @param autoRemove   also remove unused dependencies
     * @throws PackageInstaller.InstallException if removal fails
     */
    public void removeLocal(String name, String version, String channel, boolean autoRemove)
            throws PackageInstaller.InstallException {
        List<Deactivated> deactivated = new ArrayList<>();
        try {
            manager.packageRemove(name, version, channel, autoRemove,
                    () -> deactivatePackage(name, deactivated));
            moduleHost.clearPackageState(name);
        } catch (PackageInstaller.InstallException failure) {
            reactivate(deactivated, failure, List.of(name));
            throw failure;
        }
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
        List<Deactivated> deactivated = new ArrayList<>();
        AptInstaller.RemoveResult result = manager.repoRemove(name, version, channel, autoRemove,
                () -> deactivatePackage(name, deactivated));
        if (result.success()) {
            moduleHost.clearPackageState(name);
        } else {
            reactivate(deactivated, new PackageInstaller.InstallException(result.error()),
                    List.of(name));
        }
        return result;
    }

    /**
     * Activates the module JARs declared by the given installed packages.
     *
     * <p>Only files under {@code library/packages} are eligible. A package that
     * declares a JAR outside the module store is reported and skipped instead of
     * being silently ignored. If one module fails, the modules started in this
     * pass are stopped in reverse order; installed files are not touched.</p>
     *
     * @param entries installed package entries
     * @return activation result per eligible JAR
     */
    public List<ActivationResult> activate(List<PackageEntry> entries) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());
        for (PackageEntry entry : entries) {
            mergeLatest(installed, entry);
        }

        List<ActivationResult> results = new ArrayList<>();
        List<String> started = new ArrayList<>();
        List<String> activatedPackages = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (PackageEntry entry : orderByDependencies(entries)) {
            if (entry.type() != PackageType.PACKAGE) {
                continue;
            }
            if (!PlatformCompatibility.matches(entry.platform(), moduleHost.platform().name())) {
                logger.info("Package " + entry.name() + " is for platform " + entry.platform()
                        + "; not activated on " + moduleHost.platform().name());
                moduleHost.markPackageState(entry.name(), ModuleState.INSTALLED);
                continue;
            }
            List<ModuleArtifact> artifacts;
            try {
                artifacts = artifacts(entry, installed);
            } catch (IllegalStateException invalidPackage) {
                results.add(ActivationResult.failed(entry.name(),
                        invalidPackage.getMessage(), invalidPackage));
                boolean restartRequired = stopStarted(started);
                markActivationFailure(activatedPackages, entry.name(), restartRequired);
                break;
            }
            moduleHost.markPackageActivationPending(entry.name());
            boolean failed = false;
            boolean startedInPackage = false;
            for (ModuleArtifact artifact : orderArtifactsForActivation(artifacts)) {
                if (!seen.add(artifact.jar())) {
                    continue;
                }
                moduleHost.markActivationPending(artifact.moduleId());
                ActivationResult result = moduleHost.activate(artifact.jar(), artifact.dependencies());
                results.add(result);
                if (!result.isSuccess()) {
                    failed = true;
                    break;
                }
                started.add(result.moduleId());
                startedInPackage = true;
            }
            if (failed) {
                boolean restartRequired = stopStarted(started);
                markActivationFailure(activatedPackages, entry.name(), restartRequired);
                break;
            }
            if (startedInPackage) {
                activatedPackages.add(entry.name());
            } else {
                moduleHost.markPackageState(entry.name(), ModuleState.ACTIVE);
            }
        }
        for (String packageId : activatedPackages) {
            moduleHost.markPackageState(packageId, ModuleState.ACTIVE);
        }
        return List.copyOf(results);
    }

    /**
     * Activates committed packages without any file rollback. On failure it
     * stops the modules started in this pass in reverse order.
     */
    private List<PackageChange> activateCommitted(List<PackageEntry> entries,
                                                   Map<String, PackageEntry> before) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());
        for (PackageEntry entry : entries) {
            mergeLatest(installed, entry);
        }

        Map<String, List<ModuleArtifact>> newArtifacts = new LinkedHashMap<>();
        Map<String, List<ModuleArtifact>> oldArtifacts = new LinkedHashMap<>();
        Set<String> moduleIds = new HashSet<>();
        for (PackageEntry entry : orderByDependencies(entries)) {
            if (entry.type() != PackageType.PACKAGE) {
                continue;
            }
            if (!PlatformCompatibility.matches(entry.platform(), moduleHost.platform().name())) {
                PackageEntry previous = before.get(entry.name());
                if (previous != null && packageHasActiveModule(previous, before)) {
                    moduleHost.markPackageState(entry.name(), ModuleState.ACTIVATION_FAILED);
                    return List.of(PackageChange.failed(entry.name(), ModuleState.INSTALLED,
                            "New package " + entry.name() + " " + entry.version()
                                    + " is not compatible with " + moduleHost.platform().name(),
                            null));
                }
                logger.info("Package " + entry.name() + " is for platform " + entry.platform()
                        + "; not activated on " + moduleHost.platform().name());
                moduleHost.markPackageState(entry.name(), ModuleState.INSTALLED);
                continue;
            }
            try {
                List<ModuleArtifact> next = artifacts(entry, installed);
                for (ModuleArtifact artifact : next) {
                    if (!moduleIds.add(artifact.moduleId())) {
                        moduleHost.markPackageState(entry.name(), ModuleState.ACTIVATION_FAILED);
                        return List.of(PackageChange.failed(artifact.moduleId(),
                                ModuleState.INSTALLED,
                                "Duplicate module id across committed packages: "
                                        + artifact.moduleId(), null));
                    }
                }
                newArtifacts.put(entry.name(), next);
                PackageEntry previous = before.get(entry.name());
                if (previous != null && !previous.version().equals(entry.version())) {
                    oldArtifacts.put(entry.name(), artifacts(previous, before));
                }
            } catch (IllegalStateException invalidPackage) {
                moduleHost.markPackageState(entry.name(), ModuleState.ACTIVATION_FAILED);
                return List.of(PackageChange.failed(entry.name(), ModuleState.INSTALLED,
                        invalidPackage.getMessage(), invalidPackage));
            }
        }

        List<PackageChange> changes = new ArrayList<>();
        List<String> started = new ArrayList<>();
        List<String> activatedPackages = new ArrayList<>();
        for (PackageEntry entry : orderByDependencies(entries)) {
            List<ModuleArtifact> next = newArtifacts.get(entry.name());
            if (next == null) {
                continue;
            }
            List<ModuleArtifact> previousArtifacts = oldArtifacts.get(entry.name());
            Map<String, ModuleArtifact> oldById = previousArtifacts == null
                    ? Map.of() : byModuleId(previousArtifacts);
            moduleHost.markPackageActivationPending(entry.name());
            if (previousArtifacts != null) {
                List<ModuleArtifact> oldOrder = orderArtifactsForActivation(previousArtifacts);
                for (int i = oldOrder.size() - 1; i >= 0; i--) {
                    ModuleArtifact old = oldOrder.get(i);
                    if (!moduleHost.activeModules().contains(old.moduleId())) {
                        if (moduleHost.state(old.moduleId())
                                .filter(ModuleState.RESTART_REQUIRED::equals).isPresent()) {
                            changes.add(PackageChange.failed(old.moduleId(),
                                    ModuleState.RESTART_REQUIRED,
                                    "Module " + old.moduleId()
                                            + " still requires a restart; refusing to replace it",
                                    null));
                            stopStarted(started);
                            markActivationFailure(activatedPackages, entry.name(), true);
                            return List.copyOf(changes);
                        }
                        continue;
                    }
                    DeactivationResult deactivation = moduleHost.deactivate(old.moduleId());
                    if (!deactivation.isClean()) {
                        changes.add(removed(old.moduleId(), deactivation));
                        stopStarted(started);
                        markActivationFailure(activatedPackages, entry.name(), true);
                        return List.copyOf(changes);
                    }
                    if (!byModuleId(next).containsKey(old.moduleId())) {
                        changes.add(removed(old.moduleId(), deactivation));
                    }
                }
            }

            boolean startedInPackage = false;
            for (ModuleArtifact artifact : orderArtifactsForActivation(next)) {
                if (moduleHost.activeModules().contains(artifact.moduleId())) {
                    continue;
                }
                moduleHost.markActivationPending(artifact.moduleId());
                ActivationResult activation = moduleHost.activate(
                        artifact.jar(), artifact.dependencies());
                if (!activation.isSuccess()) {
                    changes.add(PackageChange.from(activation));
                    boolean restartRequired = stopStarted(started);
                    markActivationFailure(activatedPackages, entry.name(), restartRequired);
                    return List.copyOf(changes);
                }
                changes.add(oldById.containsKey(activation.moduleId())
                        ? PackageChange.upgraded(activation.moduleId())
                        : PackageChange.activated(activation.moduleId()));
                started.add(activation.moduleId());
                startedInPackage = true;
            }
            if (startedInPackage) {
                activatedPackages.add(entry.name());
            } else {
                moduleHost.markPackageState(entry.name(), ModuleState.ACTIVE);
            }
        }
        for (String packageId : activatedPackages) {
            moduleHost.markPackageState(packageId, ModuleState.ACTIVE);
        }
        return List.copyOf(changes);
    }

    private AptInstaller.InstallResult withActivation(AptInstaller.InstallResult result,
                                                      List<PackageChange> changes) {
        Optional<PackageChange> failed = changes.stream()
                .filter(change -> !change.isSuccess())
                .findFirst();
        if (failed.isPresent()) {
            PackageChange change = failed.get();
            ModuleState failure = change.state() == ModuleState.RESTART_REQUIRED
                    ? ModuleState.RESTART_REQUIRED : ModuleState.ACTIVATION_FAILED;
            return result.withRuntimeFailure(failure, change.moduleId(),
                    "Installed but activation failed for " + change.moduleId()
                            + ": " + change.message(), change.cause());
        }
        ModuleState activation = changes.stream()
                .anyMatch(change -> change.state() == ModuleState.RESTART_REQUIRED)
                ? ModuleState.RESTART_REQUIRED
                : changes.isEmpty() ? ModuleState.INSTALLED : ModuleState.ACTIVE;
        return result.withActivation(activation);
    }

    private void markActivationFailure(List<String> activatedPackages, String failingPackage,
                                       boolean restartRequired) {
        ModuleState stopped = restartRequired
                ? ModuleState.RESTART_REQUIRED : ModuleState.INSTALLED;
        for (String packageId : activatedPackages) {
            moduleHost.markPackageState(packageId, stopped);
        }
        moduleHost.markPackageState(failingPackage, restartRequired
                ? ModuleState.RESTART_REQUIRED : ModuleState.ACTIVATION_FAILED);
    }

    private boolean stopStarted(List<String> started) {
        boolean restartRequired = false;
        for (int i = started.size() - 1; i >= 0; i--) {
            String moduleId = started.get(i);
            if (!moduleHost.activeModules().contains(moduleId)) {
                continue;
            }
            DeactivationResult result = moduleHost.deactivate(moduleId);
            if (!result.isClean()) {
                restartRequired = true;
                logger.warn("Module " + moduleId
                        + " did not stop cleanly after a failed activation: "
                        + result.message());
            }
        }
        return restartRequired;
    }

    private List<PackageEntry> freshEntries(Map<String, PackageEntry> before) {
        List<PackageEntry> fresh = new ArrayList<>();
        for (PackageEntry entry : manager.packageList()) {
            PackageEntry previous = before.get(entry.name());
            if (previous == null || !previous.id().equals(entry.id())) {
                fresh.add(entry);
            }
        }
        return fresh;
    }

    private void deactivatePackage(String packageName, List<Deactivated> deactivated) {
        Map<String, PackageEntry> installed = latestByName(manager.packageList());
        PackageEntry entry = installed.get(packageName);
        if (entry == null || entry.type() != PackageType.PACKAGE) {
            if (moduleHost.activeModules().contains(packageName)) {
                deactivated.add(deactivateModule(packageName, packageName));
            }
            return;
        }

        List<ModuleArtifact> artifacts;
        try {
            artifacts = artifacts(entry, installed);
        } catch (IllegalStateException invalid) {
            logger.warn("Cannot inspect package " + packageName
                    + " before removal: " + invalid.getMessage());
            if (moduleHost.activeModules().contains(packageName)) {
                deactivated.add(deactivateModule(packageName, packageName));
            }
            return;
        }

        List<ModuleArtifact> order = orderArtifactsForActivation(artifacts);
        for (int i = order.size() - 1; i >= 0; i--) {
            ModuleArtifact artifact = order.get(i);
            if (!moduleHost.activeModules().contains(artifact.moduleId())) {
                if (moduleHost.state(artifact.moduleId())
                        .filter(ModuleState.RESTART_REQUIRED::equals).isPresent()) {
                    moduleHost.markPackageState(packageName, ModuleState.RESTART_REQUIRED);
                    throw new IllegalStateException("Module " + artifact.moduleId()
                            + " still requires a restart; refusing to remove its files");
                }
                continue;
            }
            DeactivationResult result = moduleHost.deactivate(artifact.moduleId());
            if (!result.isClean()) {
                moduleHost.markPackageState(packageName, ModuleState.RESTART_REQUIRED);
                throw new IllegalStateException("Module " + artifact.moduleId()
                        + " did not deactivate cleanly: " + result.message(), result.cause());
            }
            deactivated.add(new Deactivated(artifact.moduleId(), artifact.jar(),
                    artifact.dependencies()));
        }
    }

    private Deactivated deactivateModule(String moduleId, String packageName) {
        DeactivationResult result = moduleHost.deactivate(moduleId);
        if (!result.isClean()) {
            moduleHost.markPackageState(packageName, ModuleState.RESTART_REQUIRED);
            throw new IllegalStateException("Module " + moduleId
                    + " did not deactivate cleanly: " + result.message(), result.cause());
        }
        return new Deactivated(moduleId, null, List.of());
    }

    private void reactivate(List<Deactivated> deactivated, Exception original,
                            Collection<String> packageNames) {
        boolean restored = true;
        for (int i = deactivated.size() - 1; i >= 0; i--) {
            Deactivated module = deactivated.get(i);
            try {
                if (module.jar() == null) {
                    continue;
                }
                ActivationResult result = moduleHost.activate(module.jar(), module.dependencies());
                if (!result.isSuccess()) {
                    restored = false;
                    logger.warn("Cannot reactivate " + module.moduleId()
                            + " after a failed removal: " + result.message());
                }
            } catch (RuntimeException failure) {
                restored = false;
                original.addSuppressed(failure);
                logger.warn("Cannot reactivate " + module.moduleId()
                        + " after a failed removal: " + failure.getMessage());
            }
        }
        if (!restored) {
            for (String packageName : packageNames) {
                if (moduleHost.packageState(packageName)
                        .filter(ModuleState.RESTART_REQUIRED::equals).isEmpty()) {
                    moduleHost.markPackageState(packageName, ModuleState.ACTIVATION_FAILED);
                }
            }
        }
    }

    private PackageChange removed(String moduleId, DeactivationResult deactivation) {
        return new PackageChange(moduleId,
                deactivation.isClean() ? ModuleState.INSTALLED : ModuleState.RESTART_REQUIRED,
                deactivation.isClean(), false,
                deactivation.isClean() ? "removed from upgraded package" : deactivation.message(),
                deactivation.cause());
    }

    private record ModuleArtifact(String moduleId, Path jar,
                                  List<Path> dependencies,
                                  ModuleDescriptor descriptor) {
    }

    private record Deactivated(String moduleId, Path jar, List<Path> dependencies) {
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
