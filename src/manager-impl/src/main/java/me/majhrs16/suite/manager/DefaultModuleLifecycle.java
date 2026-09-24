package me.majhrs16.suite.manager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.kernel.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.FileOutputStream;

/**
 * Default implementation of the ModuleLifecycle SPI.
 * <p>
 * Handles the complete module lifecycle:
 * 1. Resolution (GitHub releases API, version ranges, compatibility)
 * 2. Download (with SHA256 verification)
 * 3. Relocation (package shading for dependency isolation)
 * 4. Loading (isolated ClassLoader with parent-last delegation)
 * 4. Registration (with suite kernel)
 * 5. Unregistration and unloading
 * </p>
 */
public final class DefaultModuleLifecycle implements ModuleLifecycle {

    private static final String DEFAULT_GITHUB_API_BASE = "https://api.github.com/repos/majhrs16-official/TextFormatter-Suite";
    private static final String DEFAULT_GITHUB_RELEASES_URL = "https://github.com/majhrs16-official/TextFormatter-Suite/releases/download";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build();
    private static final Gson GSON = new Gson();

    private final Path cacheDir;
    private final ConcurrentMap<String, ClassLoader> moduleClassLoaders = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ModuleDescriptor> loadedModules = new ConcurrentHashMap<>();
    private final PluginLogger logger;
    private final List<HostConfig.Repository> repositories;

    public DefaultModuleLifecycle(Path cacheDir, PluginLogger logger) {
        this(cacheDir, logger, List.of());
    }

    public DefaultModuleLifecycle(Path cacheDir, PluginLogger logger, List<HostConfig.Repository> repositories) {
        this.cacheDir = cacheDir.toAbsolutePath();
        this.logger = logger;
        this.repositories = repositories != null ? repositories : List.of();
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create cache dir: " + cacheDir, e);
        }
    }

    private String getGitHubApiBase() {
        return repositories.stream()
            .filter(r -> r.enabled() && "github".equalsIgnoreCase(r.type()))
            .map(HostConfig.Repository::url)
            .findFirst()
            .orElse(DEFAULT_GITHUB_API_BASE);
    }

    private String getGitHubReleasesUrl() {
        return repositories.stream()
            .filter(r -> r.enabled() && "github".equalsIgnoreCase(r.type()))
            .map(r -> r.url().replace("/api.github.com/repos/", "/github.com/").replace("/repos/", "/").replace("api.github.com", "github.com"))
            .findFirst()
            .orElse(DEFAULT_GITHUB_RELEASES_URL);
    }

@Override
    public ResolutionResult resolve(ModuleCoordinate coordinate, Environment env, boolean force) {
        return resolveInternal(coordinate, env, force, new HashSet<>());
    }
    
    /**
     * Internal resolution method with cycle detection.
     * @param visited Set of module coordinates currently being resolved (for cycle detection)
     */
    private ResolutionResult resolveInternal(ModuleCoordinate coordinate, Environment env, boolean force, Set<ModuleCoordinate> visited) {
        // Cycle detection
        if (visited.contains(coordinate)) {
            return ResolutionResult.failure(
                "Dependency cycle detected: " + coordinate + " -> " + visited,
                List.of()
            );
        }
        
        visited.add(coordinate);
        logger.debug("Resolving module: " + coordinate + " (force=" + force + ")");

        // Parse version range if needed
        String versionSpec = coordinate.version().toString();
        boolean isRange = versionSpec.contains(",") || versionSpec.contains("[") || versionSpec.contains("(");

        // Try each enabled repository in order (local first, then GitHub, then HTTP)
        for (HostConfig.Repository repo : repositories) {
            if (!repo.enabled()) continue;
            
            try {
                JsonArray releases;
                String baseUrl = repo.url();
                
                if ("github".equalsIgnoreCase(repo.type())) {
                    // GitHub API
                    String response = fetchWithRetry(getGitHubApiBase() + "/releases?per_page=100");
                    releases = JsonParser.parseString(response).getAsJsonArray();
                } else if ("local".equalsIgnoreCase(repo.type())) {
                    // Local filesystem - read releases.json directly
                    releases = readLocalReleases(baseUrl);
                } else if ("http".equalsIgnoreCase(repo.type())) {
                    // HTTP/HTTPS server
                    String response = fetchWithRetry(baseUrl + "/releases?per_page=100");
                    releases = JsonParser.parseString(response).getAsJsonArray();
                } else {
                    continue;
                }

                // Find matching release
                JsonObject matchingRelease = findMatchingRelease(releases, coordinate.artifact(), versionSpec, force, env);

                if (matchingRelease == null) {
                    logger.debug("No matching release in repository: " + repo.name());
                    continue; // Try next repository
                }

                // Parse release assets
                String tagName = matchingRelease.get("tag_name").getAsString();
                String publishedAt = matchingRelease.get("published_at").getAsString();
                JsonArray assets = matchingRelease.getAsJsonArray("assets");

                // Find main artifact
                String assetName = coordinate.releaseAssetName();
                JsonObject mainAsset = null;
                for (JsonElement asset : assets) {
                    JsonObject a = asset.getAsJsonObject();
                    if (a.get("name").getAsString().equals(coordinate.releaseAssetName())) {
                        mainAsset = a;
                        break;
                    }
                }

                if (mainAsset == null) {
                    logger.debug("Main artifact not found in repository: " + repo.name());
                    continue; // Try next repository
                }

                String downloadUrl = mainAsset.get("browser_download_url").getAsString();
                long sizeBytes = mainAsset.get("size").getAsLong();

                // Find SHA256 asset (separate .sha256 file)
                String sha256 = null;
                for (JsonElement asset : assets) {
                    JsonObject a = asset.getAsJsonObject();
                    String shaAssetName = a.get("name").getAsString();
                    if (shaAssetName.equals(coordinate.releaseAssetName() + ".sha256")) {
                        String sha256Url = a.get("browser_download_url").getAsString();
                        sha256 = fetchSha256(sha256Url);
                        break;
                    }
                }

                if (sha256 == null || sha256.isBlank()) {
                    logger.debug("SHA256 not found in repository: " + repo.name());
                    continue; // Try next repository
                }

                // Download module JAR to parse manifest for dependencies
                Path tempJar = downloadFile(downloadUrl, cacheDir.resolve("tmp-" + coordinate.releaseAssetName()), sha256, sizeBytes);
                
                // Parse dependencies from module manifest inside the JAR
                List<String> depStrings = parseDependenciesFromManifest(tempJar);
                
                // Fallback to release metadata if manifest parsing fails
                if (depStrings.isEmpty()) {
                    depStrings = parseDependencies(matchingRelease);
                }

                List<ResolvedModule> deps = new ArrayList<>();

                for (String depStr : depStrings) {
                    ModuleCoordinate depCoord = parseCoordinate(depStr);
                    // Pass visited set for cycle detection
                    ResolutionResult depResult = resolveInternal(depCoord, env, force, visited);
                    if (depResult instanceof ResolutionResult.Success depSuccess) {
                        deps.add(depSuccess.module());
                    } else {
                        // Clean up temp file
                        Files.deleteIfExists(tempJar);
                        return ResolutionResult.failure(
                            "Failed to resolve dependency: " + depStr + " - " + ((ResolutionResult.Failure) depResult).reason(),
                            List.of()
                        );
                    }
}

 // Clean up temp file
                Files.deleteIfExists(tempJar);

                ResolvedModule resolved = new ResolvedModule(
                    createDescriptor(matchingRelease, tagName, coordinate),
                    downloadUrl,
                    sha256,
                    sizeBytes
                );

                visited.remove(coordinate);
                return ResolutionResult.success(resolved, deps);

            } catch (Exception e) {
                logger.error("Resolution failed for " + coordinate, e);
                visited.remove(coordinate);
                return ResolutionResult.failure("Resolution error: " + e.getMessage(), List.of());
            }
        }
        
        // No repository found the module
        visited.remove(coordinate);
        return ResolutionResult.failure(
            "No matching release found for " + coordinate.artifact() + " " + versionSpec + " in any configured repository",
            List.of()
        );
    }

    /**
     * Parses module.yml manifest from a module JAR to extract dependencies.
     */
    private List<String> parseDependenciesFromManifest(Path moduleJar) {
        List<String> deps = new ArrayList<>();
        try (ZipFile zip = new ZipFile(moduleJar.toFile())) {
            ZipEntry manifestEntry = zip.getEntry("module.yml");
            if (manifestEntry == null) {
                manifestEntry = zip.getEntry("module.yaml");
            }
            if (manifestEntry != null) {
                try (InputStream is = zip.getInputStream(manifestEntry)) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> manifest = (Map<String, Object>) yaml.load(content);
                    if (manifest != null && manifest.containsKey("depends")) {
                        @SuppressWarnings("unchecked")
                        List<String> depends = (List<String>) manifest.get("depends");
                        deps.addAll(depends);
                        logger.debug("Parsed " + depends.size() + " dependencies from module manifest: " + depends);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse module manifest from " + moduleJar + ": " + e.getMessage());
        }
        return deps;
    }

    @Override
    public List<Path> download(ResolvedModule resolved, List<ResolvedModule> dependencies, Path cacheDir) {
        List<Path> paths = new ArrayList<>();

        // Download main module
        Path modulePath = downloadFile(resolved.downloadUrl(), cacheDir.resolve(resolved.descriptor().coordinate().releaseAssetName()), resolved.sha256(), resolved.sizeBytes());
        paths.add(modulePath);

        // Download dependencies
        for (ResolvedModule dep : dependencies) {
            String depUrl = getGitHubReleasesUrl() + "/" + dep.descriptor().coordinate().releaseAssetName();
            Path depPath = downloadFile(depUrl, cacheDir.resolve(dep.descriptor().coordinate().releaseAssetName()), dep.sha256(), dep.sizeBytes());
            paths.add(depPath);
        }

        return paths;
    }

    @Override
    public Path relocate(Path moduleJar, Path outputDir, Map<String, String> relocations) {
        if (relocations.isEmpty()) {
            return moduleJar; // No relocation needed
        }

        try {
            Files.createDirectories(outputDir);
            Path outputJar = outputDir.resolve(moduleJar.getFileName().toString().replace(".jar", "-relocated.jar"));

            try (JarFile jarFile = new JarFile(moduleJar.toFile());
                 JarOutputStream out = new JarOutputStream(Files.newOutputStream(outputJar))) {

                // Copy manifest with updated attributes
                Manifest manifest = jarFile.getManifest();
                if (manifest == null) {
                    manifest = new Manifest();
                    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                }
                manifest.getMainAttributes().put(new Attributes.Name("Relocated-By"), "TextFormatterSuite-Manager");
                manifest.getMainAttributes().put(new Attributes.Name("Relocation-Date"), Instant.now().toString());

                out.putNextEntry(new JarEntry(JarFile.MANIFEST_NAME));
                manifest.write(out);

                // Process each entry
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // Skip manifest
                    if (name.equals(JarFile.MANIFEST_NAME)) continue;

                    // Determine new name after relocation
                    String newName = relocateClassName(name, relocations);

                    // Read entry content
                    byte[] content = readEntry(jarFile, entry);

                    // Write relocated entry
                    JarEntry newEntry = new JarEntry(newName);
                    newEntry.setTime(entry.getTime());
                    out.putNextEntry(newEntry);
                    out.write(content);
                    out.closeEntry();
                }
            }

            return outputJar;

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to relocate JAR: " + moduleJar, e);
        }
    }

    @Override
    public ClassLoader load(Path moduleJar, List<Path> dependencyJars, ClassLoader parent) {
        List<Path> allJars = new ArrayList<>();
        allJars.add(moduleJar);
        allJars.addAll(dependencyJars);

        // Create URL array
        URL[] urls = allJars.stream()
            .map(p -> {
                try {
                    return p.toUri().toURL();
                } catch (Exception e) {
                    throw new UncheckedIOException("Invalid JAR path: " + p, new IOException(e));
                }
            })
            .toArray(URL[]::new);

        // Create isolated classloader with parent-last delegation
        return new ModuleClassLoader(urls, parent, logger);
    }

    @Override
    public boolean register(ClassLoader classLoader, ModuleDescriptor descriptor) {
        try {
            // M5: Validate module manifest before registration
            validateModuleManifest(classLoader, descriptor);

            // Per Module contract: Module is a descriptor SPI, not a service.
            // Do NOT instantiate the Module class. The module's services activate
            // through their own platform entry points (Spigot plugin / Fabric mod).
            // We only store the descriptor and classloader for discovery.
            
            loadedModules.put(descriptor.id(), descriptor);
            moduleClassLoaders.put(descriptor.id(), classLoader);
            logger.info("Registered module: " + descriptor.id());
            return true;
        } catch (Exception e) {
            logger.error("Failed to register module: " + descriptor.id(), e);
            return false;
        }
    }

    /**
     * Discovers all modules registered via this lifecycle, plus any on the
     * provided parent classloader. Used by SuiteBootstrap to get the complete
     * module set for kernel resolution.
     */
    public List<Module> discoverAll(ClassLoader parent) {
        List<Module> modules = new ArrayList<>();
        // Discover from parent (core modules on classpath)
        modules.addAll(ModuleLoader.discover(parent));
        // Discover from each registered module's classloader
        for (ClassLoader cl : moduleClassLoaders.values()) {
            modules.addAll(ModuleLoader.discover(cl));
        }
        return modules;
    }

    /**
     * Validates the module's manifest against expected schema.
     * Checks for required fields, version consistency, and required capabilities.
     */
private void validateModuleManifest(ClassLoader classLoader, ModuleDescriptor descriptor) {
        try {
            // Try to load module.yml or module.yaml from the module JAR
            var manifestStream = classLoader.getResourceAsStream("module.yml");
            if (manifestStream == null) {
                manifestStream = classLoader.getResourceAsStream("module.yaml");
            }

            if (manifestStream == null) {
                throw new IllegalStateException("Module " + descriptor.id() + " has no module.yml manifest; manifest is required for registration");
            }

            String manifestContent = new String(manifestStream.readAllBytes(), StandardCharsets.UTF_8);
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            @SuppressWarnings("unchecked")
            Map<String, Object> manifest = (Map<String, Object>) yaml.load(manifestContent);

            if (manifest == null) {
                throw new IllegalStateException("Module manifest is empty or invalid");
            }

            // Validate required fields
            validateRequiredManifestFields(descriptor, manifest);

            // Validate version matches
            if (manifest.containsKey("version")) {
                String manifestVersion = manifest.get("version").toString();
                String descriptorVersion = descriptor.version().toString();
                if (!manifestVersion.equals(descriptorVersion)) {
                    throw new IllegalStateException("Manifest version " + manifestVersion + " doesn't match descriptor version " + descriptorVersion);
                }
            }

            // Validate required capabilities exist
            if (manifest.containsKey("requires")) {
                @SuppressWarnings("unchecked")
                List<String> required = (List<String>) manifest.get("requires");
                for (String cap : required) {
                    if (!isKnownCapability(cap)) {
                        logger.warn("Module " + descriptor.id() + " declares unknown capability: " + cap);
                    }
                }
            }

            logger.debug("Module manifest validated: " + descriptor.id());

        } catch (IOException e) {
            throw new IllegalStateException("Failed to read module manifest: " + e.getMessage(), e);
        }
    }

    private void validateRequiredManifestFields(ModuleDescriptor descriptor, Map<String, Object> manifest) {
        List<String> requiredFields = List.of("name", "version", "description");
        for (String field : requiredFields) {
            if (!manifest.containsKey(field) || manifest.get(field) == null) {
                throw new IllegalStateException("Module manifest missing required field: " + field);
            }
        }

        // Validate artifact matches
        if (manifest.containsKey("artifact")) {
            String manifestArtifact = manifest.get("artifact").toString();
            String descriptorArtifact = descriptor.coordinate().artifact();
            if (!manifestArtifact.equals(descriptorArtifact)) {
                throw new IllegalStateException("Manifest artifact " + manifestArtifact + " doesn't match descriptor " + descriptorArtifact);
            }
        }
    }

    @Override
    public boolean unregister(String moduleId) {
        ModuleDescriptor descriptor = loadedModules.remove(moduleId);
        if (descriptor != null) {
            logger.info("Unregistered module: " + moduleId);
            return true;
        }
        return false;
    }

    @Override
    public void unload(String moduleId) {
        ClassLoader cl = moduleClassLoaders.remove(moduleId);
        if (cl instanceof AutoCloseable) {
            try {
                ((AutoCloseable) cl).close();
            } catch (Exception e) {
                logger.warn("Error closing classloader for " + moduleId + ": " + e.getMessage());
            }
        }
    }

    @Override
    public List<ModuleCoordinate> checkUpdates() {
        List<ModuleCoordinate> updates = new ArrayList<>();
        for (ModuleDescriptor desc : loadedModules.values()) {
            try {
                ResolutionResult result = resolve(desc.coordinate(), getCurrentEnvironment(), false);
                if (result instanceof ResolutionResult.Success success) {
                    if (success.module().descriptor().version().compareTo(desc.version()) > 0) {
                        updates.add(desc.coordinate());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to check update for " + desc.id() + ": " + e.getMessage());
            }
        }
        return updates;
    }

    @Override
    public List<ModuleCoordinate> updateSuite(Environment env, boolean force) {
        List<ModuleCoordinate> updated = new ArrayList<>();
        for (ModuleDescriptor desc : loadedModules.values()) {
            try {
                ResolutionResult result = resolve(desc.coordinate(), env, force);
                if (result instanceof ResolutionResult.Success success) {
                    ResolvedModule resolved = success.module();
                    if (resolved.descriptor().version().compareTo(desc.version()) > 0) {
                        // Download, relocate, load, register
                        List<Path> jars = download(resolved, success.dependencies(), getCacheDir());
                        Path relocated = relocate(jars.get(0), getCacheDir(), getRelocationsForModule(resolved.descriptor()));
                        ClassLoader cl = load(relocated, jars.subList(1, jars.size()), getClass().getClassLoader());
                        if (register(cl, success.module().descriptor())) {
                            updated.add(success.module().descriptor().coordinate());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to update " + desc.id() + ": " + e.getMessage());
            }
        }
        return updated;
    }

    @Override
    public Path getCacheDir() {
        return cacheDir;
    }

    @Override
    public boolean verifyChecksum(Path jar, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) return true;
        try {
            String actual = computeSha256(jar);
            return actual.equalsIgnoreCase(expectedSha256);
        } catch (Exception e) {
            logger.warn("Checksum verification failed for " + jar + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<ModuleDescriptor> getLoadedModules() {
        return new ArrayList<>(loadedModules.values());
    }

    @Override
    public List<ModuleCoordinate> discoverAvailableModules() {
        List<ModuleCoordinate> available = new ArrayList<>();
        
        // Try each enabled repository in order
        for (HostConfig.Repository repo : repositories) {
            if (!repo.enabled()) continue;
            
            try {
                String releaseUrl;
                if ("github".equalsIgnoreCase(repo.type())) {
                    releaseUrl = getGitHubApiBase() + "/releases";
                } else if ("local".equalsIgnoreCase(repo.type()) || "http".equalsIgnoreCase(repo.type())) {
                    releaseUrl = repo.url() + "/releases";
                } else {
                    continue;
                }
                
                String response = fetchWithRetry(releaseUrl + "?per_page=100");
                JsonArray releases = JsonParser.parseString(response).getAsJsonArray();

                // Collect unique artifacts from all releases
                Set<String> artifacts = new LinkedHashSet<>();
                for (JsonElement release : releases) {
                    JsonObject r = release.getAsJsonObject();
                    JsonArray assets = r.getAsJsonArray("assets");
                    for (JsonElement asset : assets) {
                        JsonObject a = asset.getAsJsonObject();
                        String name = a.get("name").getAsString();
                        if (name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")) {
                            // Extract artifact name from JAR name (e.g., "suite-textformatter-2.1.0.jar" -> "suite-textformatter")
                            String artifact = name.substring(0, name.lastIndexOf('-'));
                            artifacts.add(artifact);
                        }
                    }
                }

                // Convert to ModuleCoordinates (group:artifact:latest_version)
                for (String artifact : artifacts) {
                    // Find the latest version for this artifact
                    String latestVersion = findLatestVersion(releases, artifact);
                    if (latestVersion != null) {
                        available.add(ModuleCoordinate.of("me.majhrs16", artifact, latestVersion));
                    }
                }
                
                // If we found modules, return them (first repository wins)
                if (!available.isEmpty()) {
                    return available;
                }
            } catch (Exception e) {
                logger.warn("Failed to discover available modules from " + repo.name() + ": " + e.getMessage());
                continue; // Try next repository
            }
        }
        return available;
    }

    private String findLatestVersion(JsonArray releases, String artifact) {
        String latestVersion = null;
        for (JsonElement release : releases) {
            JsonObject r = release.getAsJsonObject();
            String tagName = r.get("tag_name").getAsString();
            String version = tagName.replace("v", "");
            JsonArray assets = r.getAsJsonArray("assets");
            for (JsonElement asset : assets) {
                JsonObject a = asset.getAsJsonObject();
                String name = a.get("name").getAsString();
                if (name.startsWith(artifact + "-") && name.endsWith(".jar")) {
                    if (latestVersion == null || SemVer.parse(version).compareTo(SemVer.parse(latestVersion)) > 0) {
                        latestVersion = version;
                    }
                    break;
                }
            }
        }
        return latestVersion;
    }

    // ============================================================
    // Private implementation methods
    // ============================================================

    private String fetchWithRetry(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            return response.body().string();
        }
    }

    private JsonArray fetchReleases() throws IOException {
        String response = fetchWithRetry(getGitHubApiBase() + "/releases?per_page=100");
        return JsonParser.parseString(response).getAsJsonArray();
    }

    /**
     * Reads releases.json directly from the local filesystem.
     * Supports file:// URLs and plain paths.
     */
    private JsonArray readLocalReleases(String baseUrl) throws IOException {
        String pathStr = baseUrl;
        if (pathStr.startsWith("file://")) {
            pathStr = pathStr.substring(7); // Remove "file://"
        }
        Path releasesFile = Path.of(pathStr).resolve("releases.json");
        
        if (!Files.exists(releasesFile)) {
            throw new IOException("releases.json not found at: " + releasesFile);
        }
        
        String content = Files.readString(releasesFile, StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonArray();
    }

    private JsonObject findMatchingRelease(JsonArray releases, String artifact, String versionSpec, boolean force, Environment env) {
        for (JsonElement release : releases) {
            JsonObject r = release.getAsJsonObject();
            String tagName = r.get("tag_name").getAsString();

            // Check if this release contains our artifact
            JsonArray assets = r.getAsJsonArray("assets");
            boolean hasArtifact = false;
            for (JsonElement asset : assets) {
                if (asset.getAsJsonObject().get("name").getAsString().contains(artifact)) {
                    hasArtifact = true;
                    break;
                }
            }
            if (!hasArtifact) continue;

            // Check version match
            String version = tagName.replace("v", "");
            if (matchesVersion(version, versionSpec) || force) {
                // Check compatibility
                if (force || isCompatibleWithEnv(r, env)) {
                    return r;
                }
            }
        }
        return null;
    }

    private boolean matchesVersion(String version, String spec) {
        if (spec.equals(version)) return true;
        // Use SemVer satisfies for range matching
        return SemVer.parse(version).satisfies(spec);
    }

    private boolean isCompatibleWithEnv(JsonObject release, Environment env) {
        // Check platform compatibility
        if (release.has("platforms")) {
            JsonArray platforms = release.getAsJsonArray("platforms");
            boolean platformMatch = false;
            for (JsonElement p : platforms) {
                if (p.getAsString().equals(env.platform())) {
                    platformMatch = true;
                    break;
                }
            }
            if (!platformMatch) return false;
        }

        // Check Java version
        if (release.has("minJavaVersion")) {
            int minJava = release.get("minJavaVersion").getAsInt();
            if (env.javaVersion() < minJava) return false;
        }
        if (release.has("maxJavaVersion")) {
            int maxJava = release.get("maxJavaVersion").getAsInt();
            if (env.javaVersion() > maxJava) return false;
        }

        // Check core API version
        if (release.has("requiredCoreApi")) {
            String requiredApi = release.get("requiredCoreApi").getAsString();
            if (!SemVer.isValid(requiredApi)) return false;
            SemVer required = SemVer.parse(requiredApi);
            if (env.coreApiVersion().compareTo(required) < 0) return false;
        }

        // Check Minecraft version if specified
        if (release.has("minecraftVersion")) {
            String requiredMc = release.get("minecraftVersion").getAsString();
            if (!requiredMc.equals(env.minecraftVersion())) return false;
        }

        // Check required capabilities
        if (release.has("requires")) {
            JsonArray requires = release.getAsJsonArray("requires");
            for (JsonElement cap : requires) {
                if (!env.hasCapability(cap.getAsString())) return false;
            }
        }

        // Check dependencies (module coordinates that must be installed)
        if (release.has("dependencies")) {
            JsonArray deps = release.getAsJsonArray("dependencies");
            for (JsonElement dep : deps) {
                String depStr = dep.getAsString();
                // Format: group:artifact:version or group:artifact:version:classifier
                String[] parts = depStr.split(":");
                if (parts.length >= 3) {
                    String depId = parts[0] + ":" + parts[1];
                    String depVersion = parts[2];
                    if (!env.hasModule(depId, SemVer.parse(depVersion))) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private List<String> getAvailableVersions(JsonArray releases, String artifact) {
        List<String> versions = new ArrayList<>();
        for (JsonElement release : releases) {
            JsonObject r = release.getAsJsonObject();
            JsonArray assets = r.getAsJsonArray("assets");
            for (JsonElement asset : assets) {
                if (asset.getAsJsonObject().get("name").getAsString().contains(artifact)) {
                    versions.add(r.get("tag_name").getAsString().replace("v", ""));
                    break;
                }
            }
        }
        return versions;
    }

    private List<String> parseDependencies(JsonObject release) {
        List<String> deps = new ArrayList<>();

        // 1. Try to parse from release body (e.g., "dependencies:" section)
        if (release.has("body") && !release.get("body").isJsonNull()) {
            String body = release.get("body").getAsString();
            deps.addAll(parseDependenciesFromBody(body));
        }

        // 2. Try to find and parse a manifest file from assets
        // This would require downloading and parsing a manifest asset
        // For now, we'll leave this as a TODO for when assets are available

        return deps;
    }

    private List<String> parseDependenciesFromBody(String body) {
        List<String> deps = new ArrayList<>();
        if (body == null || body.isBlank()) return deps;

        // Look for a "dependencies:" section in the body
        // Format: 
        // dependencies:
        //   - group:artifact:version
        //   - group:artifact:version:classifier
        String[] lines = body.split("\n");
        boolean inDepsSection = false;
        for (String line : lines) {
            line = line.trim();
            if (line.equalsIgnoreCase("dependencies:") || line.equalsIgnoreCase("dependencies:")) {
                inDepsSection = true;
                continue;
            }
            if (inDepsSection) {
                if (line.isEmpty() || !line.startsWith("-")) {
                    // End of dependencies section
                    if (!line.isEmpty() && !line.startsWith(" ")) {
                        inDepsSection = false;
                    }
                }
                if (inDepsSection && line.startsWith("-")) {
                    String dep = line.substring(1).trim();
                    if (!dep.isEmpty()) {
                        deps.add(dep);
                    }
                }
            }
        }
        return deps;
    }

    private ModuleCoordinate parseCoordinate(String depStr) {
        // Parse "group:artifact:version" or "group:artifact:version:classifier"
        String[] parts = depStr.split(":");
        if (parts.length == 3) {
            return ModuleCoordinate.of(parts[0], parts[1], parts[2]);
        } else if (parts.length == 4) {
            return ModuleCoordinate.of(parts[0], parts[1], parts[2], parts[3]);
        }
        throw new IllegalArgumentException("Invalid coordinate: " + depStr);
    }

    private ModuleDescriptor createDescriptor(JsonObject release, String tagName, ModuleCoordinate coord) {
        String version = tagName.replace("v", "");
        String name = release.get("name").getAsString();
        String description = release.has("body") ? release.get("body").getAsString() : "";
        List<String> depStrings = parseDependencies(release);
        List<String> depCoords = new ArrayList<>();
        for (String depStr : depStrings) {
            try {
                depCoords.add(parseCoordinate(depStr).toCoordinateString());
            } catch (Exception e) {
                logger.warn("Failed to parse dependency: " + depStr + " - " + e.getMessage());
            }
        }
        return new ModuleDescriptor(
            ModuleCoordinate.of(coord.group(), coord.artifact(), version),
            name, description, "TextFormatter Suite Team",
            "https://github.com/majhrs16-official/TextFormatter-Suite",
            "GPL-3.0",
            SemVer.of(2, 1, 0), // requiredCoreApi
            "17", // minJavaVersion
            "21", // maxJavaVersion
            List.of("spigot", "fabric", "velocity", "common"),
            depCoords,
            Set.of(), // provides
            Set.of(), // requires
            Map.of() // properties
        );
    }

    private Path downloadFile(String url, Path dest, String expectedSha256, long expectedSize) {
        try {
            // Check if already cached and valid
            if (Files.exists(dest)) {
                if (verifyChecksum(dest, expectedSha256) && Files.size(dest) == expectedSize) {
                    return dest;
                }
            }

            Files.createDirectories(dest.getParent());

            if (url.startsWith("file://")) {
                // Local file - copy directly from filesystem
                String filePath = url.substring(7); // Remove "file://"
                Path sourcePath = Path.of(filePath);
                if (!Files.exists(sourcePath)) {
                    throw new IOException("Local file not found: " + sourcePath);
                }
                Files.copy(sourcePath, dest, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // HTTP/HTTPS - use OkHttp
                Request request = new Request.Builder().url(url).build();
                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP " + response.code() + " for " + url);
                    }

                    try (InputStream in = response.body().byteStream();
                         OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        in.transferTo(out);
                    }
                }
            }

            // Verify
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                if (!verifyChecksum(dest, expectedSha256)) {
                    Files.deleteIfExists(dest);
                    throw new IOException("Checksum mismatch for " + dest);
                }
            }
            if (expectedSize > 0 && Files.size(dest) != expectedSize) {
                Files.deleteIfExists(dest);
                throw new IOException("Size mismatch for " + dest);
            }

            return dest;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to download " + url, e);
        }
    }

    private String fetchSha256(String url) {
        try {
            if (url.startsWith("file://")) {
                String filePath = url.substring(7);
                Path sourcePath = Path.of(filePath);
                if (!Files.exists(sourcePath)) {
                    return null;
                }
                return Files.readString(sourcePath, StandardCharsets.UTF_8).trim();
            }
            return fetchWithRetry(url).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readEntry(JarFile jarFile, JarEntry entry) throws IOException {
        try (InputStream is = jarFile.getInputStream(entry)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private String computeSha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
        try (InputStream is = Files.newInputStream(file)) {
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
        return hex.toString();
    }

    private Map<String, String> getRelocationsForModule(ModuleDescriptor desc) {
        Map<String, String> relocations = new HashMap<>();
        // Relocate all non-API packages
        String base = "me.majhrs16.suite." + desc.coordinate().artifact().replace("suite-", "");
        relocations.put(base, base + ".relocated");
        relocations.put("org.apache.commons", "me.majhrs16.suite.relocated.org.apache.commons");
        relocations.put("com.google", "me.majhrs16.suite.relocated.com.google");
        relocations.put("org.yaml", "me.majhrs16.suite.relocated.org.yaml");
        relocations.put("com.fasterxml.jackson", "me.majhrs16.suite.relocated.com.fasterxml.jackson");
        return relocations;
    }

    private String relocateClassName(String name, Map<String, String> relocations) {
        for (Map.Entry<String, String> entry : relocations.entrySet()) {
            if (name.startsWith(entry.getKey())) {
                return entry.getValue() + name.substring(entry.getKey().length());
            }
        }
        return name;
    }

    private Environment getCurrentEnvironment() {
        Map<String, String> sysProps = new HashMap<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            sysProps.put(key, System.getProperty(key));
        }
        return new Environment(
            "spigot", // or detect
            Runtime.version().feature(),
            "1.20.6",
            SemVer.of(2, 1, 0),
            loadedModules.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().version().toString())),
            Set.of(),
            sysProps
        );
    }

    private boolean isKnownCapability(String capability) {
        return switch (capability) {
            case "channel-api", "iflow-api", "textformatter-api", "translation-api",
                 "spigot", "fabric", "velocity", "common",
                 "module-api", "extension-api", "observability-api",
                 "sync-api", "presets-api" -> true;
            default -> false;
        };
    }

    // ModuleClassLoader with parent-last delegation
    private static final class ModuleClassLoader extends URLClassLoader {
        private final PluginLogger logger;

        public ModuleClassLoader(URL[] urls, ClassLoader parent, PluginLogger logger) {
            super(urls, parent);
            this.logger = logger;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // Parent-last for module classes (our packages)
            if (name.startsWith("me.majhrs16.suite.") && !name.startsWith("me.majhrs16.suite.api.")) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                }
                return loaded;
            }
            // Parent-first for everything else (API, JDK, etc.)
            return super.loadClass(name, resolve);
        }

        @Override
        public void close() throws IOException {
            // Close any resources if needed
        }
    }
}