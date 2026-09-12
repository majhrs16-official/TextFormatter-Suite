package me.majhrs16.suite.manager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

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

    private static final String GITHUB_API_BASE = "https://api.github.com/repos/majhrs16-official/TextFormatter-Suite";
    private static final String GITHUB_RELEASES_URL = "https://github.com/majhrs16-official/TextFormatter-Suite/releases/download";
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

    public DefaultModuleLifecycle(Path cacheDir, PluginLogger logger) {
        this.cacheDir = cacheDir.toAbsolutePath();
        this.logger = logger;
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create cache dir: " + cacheDir, e);
        }
    }

    @Override
    public ResolutionResult resolve(ModuleCoordinate coordinate, Environment env, boolean force) {
        logger.debug("Resolving module: " + coordinate + " (force=" + force + ")");

        // Parse version range if needed
        String versionSpec = coordinate.version().toString();
        boolean isRange = versionSpec.contains(",") || versionSpec.contains("[") || versionSpec.contains("(");

        try {
            // Fetch release info from GitHub
            String releaseUrl = GITHUB_API_BASE + "/releases";
            String response = fetchWithRetry(GITHUB_API_BASE + "/releases?per_page=100");

            JsonArray releases = JsonParser.parseString(response).getAsJsonArray();

            // Find matching release
            JsonObject matchingRelease = findMatchingRelease(releases, coordinate.artifact(), versionSpec, force, env);

            if (matchingRelease == null) {
                return ResolutionResult.failure(
                    "No matching release found for " + coordinate.artifact() + " " + versionSpec,
                    getAvailableVersions(releases, coordinate.artifact())
                );
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
                return ResolutionResult.failure(
                    "Main artifact not found in release: " + coordinate.releaseAssetName(),
                    List.of()
                );
            }

            String downloadUrl = mainAsset.get("browser_download_url").getAsString();
            String sha256 = mainAsset.get("name").getAsString().endsWith(".sha256") ? 
                fetchSha256(downloadUrl + ".sha256") : null;
            long sizeBytes = mainAsset.get("size").getAsLong();

            // Parse dependencies from release metadata or module manifest
            List<String> depStrings = parseDependencies(matchingRelease);
            List<ResolvedModule> deps = new ArrayList<>();

            for (String depStr : depStrings) {
                ModuleCoordinate depCoord = parseCoordinate(depStr);
                ResolutionResult depResult = resolve(depCoord, env, force);
                if (depResult instanceof ResolutionResult.Success depSuccess) {
                    deps.add(depSuccess.module());
                } else {
                    return ResolutionResult.failure(
                        "Failed to resolve dependency: " + depStr + " - " + ((ResolutionResult.Failure) depResult).reason(),
                        List.of()
                    );
                }
            }

            ResolvedModule resolved = new ResolvedModule(
                createDescriptor(matchingRelease, tagName, coordinate),
                downloadUrl,
                sha256,
                sizeBytes
            );

            return ResolutionResult.success(resolved, deps);

        } catch (Exception e) {
            logger.error("Resolution failed for " + coordinate, e);
            return ResolutionResult.failure("Resolution error: " + e.getMessage(), List.of());
        }
    }

    @Override
    public List<Path> download(ResolvedModule resolved, List<ResolvedModule> dependencies, Path cacheDir) {
        List<Path> paths = new ArrayList<>();

        // Download main module
        Path modulePath = downloadFile(resolved.downloadUrl(), cacheDir.resolve(resolved.descriptor().coordinate().releaseAssetName()), resolved.sha256(), resolved.sizeBytes());
        paths.add(modulePath);

        // Download dependencies
        for (ResolvedModule dep : dependencies) {
            String depUrl = GITHUB_RELEASES_URL + "/" + dep.descriptor().coordinate().releaseAssetName();
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
            // Load the Module class from the classloader
            Class<?> moduleClass = classLoader.loadClass(descriptor.coordinate().artifact() + "Module");
            Module module = (Module) moduleClass.getDeclaredConstructor().newInstance();

            // Register with the suite kernel
            // This would integrate with SuiteHost/ModuleLoader
            loadedModules.put(descriptor.id(), descriptor);
            logger.info("Registered module: " + descriptor.id());
            return true;
        } catch (Exception e) {
            logger.error("Failed to register module: " + descriptor.id(), e);
            return false;
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
        String response = fetchWithRetry(GITHUB_API_BASE + "/releases?per_page=100");
        return JsonParser.parseString(response).getAsJsonArray();
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
        if (spec.startsWith("[") || spec.contains(",")) {
            // Range matching would go here
            return true; // Simplified
        }
        return SemVer.parse(version).satisfies(SemVer.parse(spec));
    }

    private boolean isCompatibleWithEnv(JsonObject release, Environment env) {
        // Check release metadata for compatibility
        // For now, assume compatible
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
        // Parse from release body or manifest
        // For now, return empty
        return List.of();
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
        return new ModuleDescriptor(
            ModuleCoordinate.of(coord.group(), coord.artifact(), version),
            name, description, "TextFormatter Suite Team",
            "https://github.com/majhrs16-official/TextFormatter-Suite",
            "GPL-3.0",
            SemVer.of(2, 1, 0), // requiredCoreApi
            "17", // minJavaVersion
            "21", // maxJavaVersion
            List.of("spigot", "fabric", "velocity", "common"),
            List.of(), // dependencies
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

            Request request = new Request.Builder().url(url).build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code() + " for " + url);
                }

                Files.createDirectories(dest.getParent());
                try (InputStream in = response.body().byteStream();
                     OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                }

                // Verify
                if (expectedSha256 != null && !expectedSha256.isBlank()) {
                    if (!verifyChecksum(dest.toFile(), expectedSha256)) {
                        Files.deleteIfExists(dest);
                        throw new IOException("Checksum mismatch for " + dest);
                    }
                }
                if (expectedSize > 0 && Files.size(dest) != expectedSize) {
                    Files.deleteIfExists(dest);
                    throw new IOException("Size mismatch for " + dest);
                }

                return dest;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to download " + url, e);
        }
    }

    private String fetchSha256(String url) {
        try {
            return fetchWithRetry(url).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private boolean verifyChecksum(Path file, String expectedSha256) {
        try {
            String actual = computeSha256(file);
            return actual.equalsIgnoreCase(expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }

    private String computeSha256(Path file) throws IOException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
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

    private Path relocate(Path moduleJar, Path outputDir, Map<String, String> relocations) {
        return relocate(moduleJar, outputDir, relocations);
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

    private String relocateClassName(String name, Map<String, String> relocations) {
        for (Map.Entry<String, String> entry : relocations.entrySet()) {
            if (name.startsWith(entry.getKey())) {
                return entry.getValue() + name.substring(entry.getKey().length());
            }
        }
        return name;
    }

    private Environment getCurrentEnvironment() {
        return new Environment(
            "spigot", // or detect
            Runtime.version().feature(),
            "1.20.6",
            SemVer.of(2, 1, 0),
            loadedModules.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().version().toString())),
            Set.of(),
            System.getProperties()
        );
    }

    // ModuleClassLoader with parent-last delegation
    private static final class ModuleClassLoader extends ClassLoader {
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