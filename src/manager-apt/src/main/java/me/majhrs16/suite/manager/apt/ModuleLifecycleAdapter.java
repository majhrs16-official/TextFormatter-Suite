package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.ModuleCoordinate;
import me.majhrs16.suite.manager.ModuleDescriptor;
import me.majhrs16.suite.manager.ModuleLifecycle;
import me.majhrs16.suite.manager.Environment;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.Version;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compatibility adapter that implements the old ModuleLifecycle interface
 * using the new ManagerFacade (APT/dpkg architecture).
 * 
 * <p><strong>Important:</strong> The new APT/dpkg architecture installs packages to disk
 * and requires a server restart to load them. Dynamic runtime loading/unloading
 * (download, relocate, load, register, unregister, unload) is NOT supported.
 * This adapter provides no-op implementations for those methods to maintain
 * API compatibility without throwing {@link UnsupportedOperationException}.</p>
 */
public final class ModuleLifecycleAdapter implements ModuleLifecycle {

    private final ManagerFacade facade;
    private final PluginLogger logger;

    public ModuleLifecycleAdapter(ManagerFacade facade, PluginLogger logger) {
        this.facade = facade;
        this.logger = logger;
    }

    @Override
    public ResolutionResult resolve(ModuleCoordinate coordinate, Environment env, boolean force) {
        // Convert to new package system
        String pkgName = coordinate.artifact().replace("suite-", "");
        String version = coordinate.version().toString();
        String channel = "stable"; // Default channel
        
        try {
            AptInstaller.InstallResult result = facade.repoInstall(
                    List.of(pkgName), version, channel, force);
            
            if (!result.success()) {
                return ResolutionResult.failure(result.error(), List.of());
            }
            
            // Return a dummy resolved module (the actual install happens in install command)
            ModuleDescriptor desc = new ModuleDescriptor(
                    coordinate,
                    pkgName,
                    "",
                    "TextFormatter Suite Team",
                    "https://github.com/majhrs16-official/TextFormatter-Suite",
                    "GPL-3.0",
                    SemVer.of(2, 1, 0),
                    "17", "21",
                    List.of(env.platform()),
                    List.of(),
                    Set.of(),
                    Set.of(),
                    Map.of("channel", channel, "type", "package")
            );
            
            ResolvedModule resolved = new ResolvedModule(desc, "", "", 0);
            return ResolutionResult.success(resolved, List.of());
            
        } catch (Exception e) {
            return ResolutionResult.failure("Resolution failed: " + e.getMessage(), List.of());
        }
    }

    @Override
    public List<Path> download(ResolvedModule resolved, List<ResolvedModule> dependencies, Path cacheDir) {
        // In APT/dpkg architecture, download happens during install via repoInstall()
        // This method is not used by the new system.
        logger.debug("download() called - not supported in APT/dpkg architecture; use repoInstall()");
        return Collections.emptyList();
    }

    @Override
    public Path relocate(Path moduleJar, Path outputDir, Map<String, String> relocations) {
        // In APT/dpkg architecture, bytecode relocation is handled during package installation
        // This method is not used by the new system.
        logger.debug("relocate() called - not supported in APT/dpkg architecture");
        return moduleJar;
    }

    @Override
    public ClassLoader load(Path moduleJar, List<Path> dependencyJars, ClassLoader parent) {
        // In APT/dpkg architecture, modules are loaded by the platform's classloader at startup
        // after being installed to disk. Dynamic classloader creation is not supported.
        logger.debug("load() called - not supported in APT/dpkg architecture; modules load on restart");
        return parent;
    }

    @Override
    public boolean register(ClassLoader classLoader, ModuleDescriptor descriptor) {
        // In APT/dpkg architecture, registration happens via package installation to disk
        // and is persisted across restarts. Dynamic registration is not supported.
        logger.debug("register() called - not supported in APT/dpkg architecture; use repoInstall()");
        return false;
    }

    @Override
    public boolean unregister(String moduleId) {
        // In APT/dpkg architecture, unregistration happens via package removal from disk
        // and takes effect after restart. Dynamic unregistration is not supported.
        logger.debug("unregister() called - not supported in APT/dpkg architecture; use repoRemove()");
        return false;
    }

    @Override
    public void unload(String moduleId) {
        // In APT/dpkg architecture, unloading requires server restart after package removal.
        // Dynamic unloading is not supported.
        logger.debug("unload() called - not supported in APT/dpkg architecture; requires restart");
    }

    @Override
    public List<ModuleCoordinate> checkUpdates() {
        Map<String, List<RepositoryManager.PackageCandidate>> available = facade.getAvailablePackages();
        List<PackageEntry> installed = facade.packageList();
        
        return installed.stream()
                .flatMap(installedPkg -> {
                    List<RepositoryManager.PackageCandidate> candidates = available.get(installedPkg.name());
                    if (candidates == null || candidates.isEmpty()) {
                        return Stream.empty();
                    }
                    RepositoryManager.PackageCandidate best = candidates.get(0);
                    if (Version.parse(best.version()).compareTo(Version.parse(installedPkg.version())) > 0) {
                        return Stream.of(ModuleCoordinate.of("me.majhrs16", installedPkg.name(), best.version()));
                    }
                    return Stream.empty();
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ModuleCoordinate> updateSuite(Environment env, boolean force) {
        AptInstaller.InstallResult result = facade.repoUpgrade();
        if (!result.success()) {
            logger.error("Suite update failed: " + result.error());
            return List.of();
        }
        return result.packages().stream()
                .map(p -> {
                    String[] parts = p.split(" ");
                    return ModuleCoordinate.of("me.majhrs16", parts[0], parts[1].replace("v", ""));
                })
                .collect(Collectors.toList());
    }

    @Override
    public Path getCacheDir() {
        return facade.getDatabase().getTmpDir();
    }

    @Override
    public boolean verifyChecksum(Path jar, String expectedSha256) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var is = java.nio.file.Files.newInputStream(jar)) {
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
            return hex.toString().equalsIgnoreCase(expectedSha256);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<ModuleDescriptor> getLoadedModules() {
        return facade.getLoadedModules();
    }

    @Override
    public List<ModuleCoordinate> discoverAvailableModules() {
        Map<String, List<RepositoryManager.PackageCandidate>> available = facade.getAvailablePackages();
        return available.entrySet().stream()
                .map(e -> ModuleCoordinate.of("me.majhrs16", e.getKey(), e.getValue().get(0).version()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Module> discoverAll(ClassLoader parent) {
        return facade.discoverAll(parent);
    }
}