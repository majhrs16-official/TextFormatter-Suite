package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.ModuleCoordinate;
import me.majhrs16.suite.manager.ModuleDescriptor;
import me.majhrs16.suite.manager.ModuleLifecycle;
import me.majhrs16.suite.manager.Environment;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.PackageEntry;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compatibility adapter that implements the old ModuleLifecycle interface
 * using the new ManagerFacade (APT/dpkg architecture).
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
        // In new system, download happens during install
        return List.of();
    }

    @Override
    public Path relocate(Path moduleJar, Path outputDir, Map<String, String> relocations) {
        // In new system, relocation is handled by package installer
        return moduleJar;
    }

    @Override
    public ClassLoader load(Path moduleJar, List<Path> dependencyJars, ClassLoader parent) {
        // In new system, loading is handled by package installer
        return parent;
    }

    @Override
    public boolean register(ClassLoader classLoader, ModuleDescriptor descriptor) {
        // In new system, registration is handled by package installer
        return true;
    }

    @Override
    public boolean unregister(String moduleId) {
        // In new system, removal is handled by package installer
        return true;
    }

    @Override
    public void unload(String moduleId) {
        // In new system, unloading is handled by package installer
    }

    @Override
    public List<ModuleCoordinate> checkUpdates() {
        List<PackageEntry> updates = facade.checkUpdates();
        return updates.stream()
                .map(e -> ModuleCoordinate.of("me.majhrs16", e.name(), e.version(), (String) null))
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