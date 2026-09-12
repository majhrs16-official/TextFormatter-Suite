package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.SemVer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Core SPI for the module manager lifecycle.
 * <p>
 * This is the central interface that implementations must provide.
 * It handles the complete lifecycle: resolve -> download -> relocate -> load -> register -> unregister -> unload.
 * </p>
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

    /**
     * Resolves a module coordinate to a downloadable artifact with all transitive dependencies.
     *
     * @param coordinate the module coordinate to resolve (version may be a range like "[2.1.0,3.0.0)")
     * @param env        the current environment for compatibility checks
     * @param force      if true, bypass compatibility checks (platform, Java version, etc.)
     * @return resolution result with the module and all transitive dependencies
     */
    ResolutionResult resolve(ModuleCoordinate coordinate, Environment env, boolean force);

    /**
     * Downloads a resolved module and its dependencies to the local cache.
     * Verifies SHA256 checksums.
     *
     * @param resolved the resolved module with download URL and SHA256
     * @param cacheDir local directory to store downloaded JARs
     * @return paths to downloaded JARs (module first, then dependencies)
     */
    List<Path> download(ResolvedModule resolved, List<ResolvedModule> dependencies, Path cacheDir);

    /**
     * Relocates dependencies in a module JAR to avoid classpath conflicts.
     * Uses package relocation (shading) to prefix all non-API packages.
     *
     * @param moduleJar   path to the module JAR
     * @param outputDir   directory to write relocated JAR
     * @param relocations map of original package prefix -> relocated prefix
     * @return path to relocated JAR
     */
    Path relocate(Path moduleJar, Path outputDir, java.util.Map<String, String> relocations);

    /**
     * Loads a module JAR (and its relocated dependencies) into a new isolated ClassLoader.
     * The ClassLoader is a child of the provided parent, with parent-last delegation for module classes.
     *
     * @param moduleJar       the module JAR (already relocated)
     * @param dependencyJars  relocated dependency JARs
     * @param parent          parent ClassLoader (usually the suite's classloader)
     * @return a new ClassLoader that can load the module classes
     */
    ClassLoader load(Path moduleJar, List<Path> dependencyJars, ClassLoader parent);

    /**
     * Registers a loaded module with the suite's kernel, making its services available.
     *
     * @param classLoader the module's ClassLoader
     * @param descriptor    the module descriptor
     * @return true if registration succeeded
     */
    boolean register(ClassLoader classLoader, ModuleDescriptor descriptor);

    /**
     * Unregisters a module from the suite's kernel.
     *
     * @param moduleId the module coordinate ID
     * @return true if unregistration succeeded
     */
    boolean unregister(String moduleId);

    /**
     * Unloads a module by closing its ClassLoader and cleaning up resources.
     *
     * @param moduleId the module coordinate ID
     */
    void unload(String moduleId);

    /**
     * Checks for available updates for all installed modules.
     *
     * @return list of modules with available updates
     */
    List<ModuleCoordinate> checkUpdates();

    /**
     * Performs a full suite update: resolves latest compatible versions,
     * downloads, relocates, and registers them.
     *
     * @param env  current environment
     * @param force if true, update even if not strictly compatible
     * @return list of updated module coordinates
     */
    List<ModuleCoordinate> updateSuite(Environment env, boolean force);

    /**
     * Gets the local cache directory for downloaded modules.
     */
    Path getCacheDir();

    /**
     * Verifies a JAR's SHA256 checksum.
     */
    boolean verifyChecksum(Path jar, String expectedSha256);

    /**
     * Gets the list of currently loaded modules.
     */
    List<ModuleDescriptor> getLoadedModules();
}