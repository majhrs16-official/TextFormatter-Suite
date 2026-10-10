package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.SemVer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Compatibility SPI for the module manager lifecycle.
 * This is the central interface that implementations must provide.
 * It handles the complete lifecycle: resolve -> download -> relocate -> load -> register -> unregister -> unload.
 */
public interface ModuleLifecycle {

    /**
     * Represents a resolved module with its download URL and metadata.
     */
    record ResolvedModule(
        ModuleDescriptor descriptor,
        String downloadUrl,
        String sha256,
        long sizeBytes
    ) {}

    /**
     * Result of a resolution operation.
     */
    sealed interface ResolutionResult permits ResolutionResult.Success, ResolutionResult.Failure {
        static Success success(ResolvedModule module, List<ResolvedModule> dependencies) {
            return new Success(module, dependencies);
        }
        static Failure failure(String reason, List<String> candidates) {
            return new Failure(reason, candidates);
        }

        record Success(ResolvedModule module, List<ResolvedModule> dependencies) implements ResolutionResult {}
        record Failure(String reason, List<String> candidates) implements ResolutionResult {}
    }

    ResolutionResult resolve(ModuleCoordinate coordinate, Environment env, boolean force);

    List<Path> download(ResolvedModule resolved, List<ResolvedModule> dependencies, Path cacheDir);

    Path relocate(Path moduleJar, Path outputDir, java.util.Map<String, String> relocations);

    ClassLoader load(Path moduleJar, List<Path> dependencyJars, ClassLoader parent);

    boolean register(ClassLoader classLoader, ModuleDescriptor descriptor);

    boolean unregister(String moduleId);

    void unload(String moduleId);

    List<ModuleCoordinate> checkUpdates();

    List<ModuleCoordinate> updateSuite(Environment env, boolean force);

    Path getCacheDir();

    boolean verifyChecksum(Path jar, String expectedSha256);

    List<ModuleDescriptor> getLoadedModules();

    List<ModuleCoordinate> discoverAvailableModules();

    List<Module> discoverAll(ClassLoader parent);
}