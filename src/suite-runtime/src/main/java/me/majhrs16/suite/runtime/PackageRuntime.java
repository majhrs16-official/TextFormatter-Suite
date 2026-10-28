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
import java.util.Set;

import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.AptInstaller;
import me.majhrs16.suite.manager.apt.ManagerFacade;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;

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
 * other.</p>
 */
public final class PackageRuntime {

    private final ManagerFacade manager;
    private final ModuleHost moduleHost;
    private final PluginLogger logger;
    private final Path installRoot;
    private final Path packagesRoot;

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
    }

    /**
     * Installs a local distribution ZIP and activates the module JARs it
     * installs.
     *
     * @param zipPath distribution ZIP
     * @param force   pass-through to the package installer
     * @return activation result per module JAR found in the installed packages
     * @throws PackageInstaller.InstallException if installation fails
     */
    public List<ActivationResult> installLocal(Path zipPath, boolean force)
            throws PackageInstaller.InstallException {
        return activate(manager.packageInstall(zipPath, force));
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
        Set<String> before = installedKeys();
        AptInstaller.InstallResult result = manager.repoInstall(packages, version, channel, force);
        activateNewInstalls(before, result);
        return result;
    }

    /**
     * Upgrades every installed package and activates the new versions after a
     * successful upgrade.
     *
     * @return the manager result
     */
    public AptInstaller.InstallResult upgrade() {
        Set<String> before = installedKeys();
        AptInstaller.InstallResult result = manager.repoUpgrade();
        activateNewInstalls(before, result);
        return result;
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
        List<ActivationResult> results = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (PackageEntry entry : orderByDependencies(entries)) {
            if (entry.type() == PackageType.DEPENDENCY) {
                continue;
            }
            for (FileEntry file : entry.files().values()) {
                if (!isJar(file.path())) {
                    continue;
                }
                Path jar = installRoot.resolve(file.path()).normalize();
                if (!jar.startsWith(packagesRoot)) {
                    logger.warn("Package " + entry.name() + " declares a JAR outside library/packages: "
                            + file.path() + "; not activated");
                    continue;
                }
                if (!Files.isRegularFile(jar)) {
                    logger.warn("Installed module JAR is missing: " + jar + "; not activated");
                    continue;
                }
                if (seen.add(jar)) {
                    results.add(moduleHost.activate(jar));
                }
            }
        }
        return List.copyOf(results);
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

    private void activateNewInstalls(Set<String> before, AptInstaller.InstallResult result) {
        if (!result.success() || result.requiresConfirmation()) {
            return;
        }
        List<PackageEntry> fresh = new ArrayList<>();
        for (PackageEntry entry : manager.packageList()) {
            if (!before.contains(entry.id())) {
                fresh.add(entry);
            }
        }
        activate(fresh);
    }

    private Set<String> installedKeys() {
        Set<String> keys = new HashSet<>();
        for (PackageEntry entry : manager.packageList()) {
            keys.add(entry.id());
        }
        return keys;
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

    private static boolean isJar(String path) {
        return path.length() > 4 && path.regionMatches(true, path.length() - 4, ".jar", 0, 4);
    }
}
