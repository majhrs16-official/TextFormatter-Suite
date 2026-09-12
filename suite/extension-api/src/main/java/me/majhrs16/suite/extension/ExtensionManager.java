package me.majhrs16.suite.extension;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Manages the lifecycle of extensions (addons).
 * <p>
 * Handles discovery, loading, dependency resolution, enabling/disabling,
 * and hot-reloading of extensions.
 * </p>
 */
public final class ExtensionManager {

    private final PluginLogger logger;
    private final ExtensionContextProvider contextProvider;
    private final Map<String, LoadedExtension> loaded = new ConcurrentHashMap<>();
    private final Map<String, ExtensionDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Path extensionsDir;
    private volatile boolean running = false;

    public ExtensionManager(PluginLogger logger, ExtensionContextProvider contextProvider, Path extensionsDir) {
        this.logger = logger;
        this.contextProvider = contextProvider;
        this.extensionsDir = extensionsDir;
    }

    /**
     * Scans the extensions directory and discovers all available extensions.
     */
    public void discover() {
        if (!Files.exists(extensionsDir)) {
            try {
                Files.createDirectories(extensionsDir);
            } catch (IOException e) {
                logger.error("Failed to create extensions dir: " + e.getMessage());
            }
            return;
        }

        try (var stream = Files.list(extensionsDir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                .forEach(this::loadDescriptor);
        } catch (IOException e) {
            logger.error("Failed to scan extensions dir: " + e.getMessage());
        }
    }

    /**
     * Loads the extension descriptor from a JAR's MANIFEST.MF.
     */
    private void loadDescriptor(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                logger.warn("No manifest in " + jarPath.getFileName());
                return;
            }

            var attrs = manifest.getMainAttributes();
            String id = attrs.getValue("Extension-Id");
            if (id == null || id.isBlank()) {
                logger.warn("Missing Extension-Id in " + jarPath.getFileName());
                return;
            }

            ExtensionDescriptor desc = ExtensionDescriptor.fromManifest(jarPath, manifest);
            descriptors.put(id, desc);
            logger.info("Discovered extension: " + id + " v" + desc.version());
        } catch (Exception e) {
            logger.error("Failed to load descriptor from " + jarPath.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * Enables an extension by ID.
     * Resolves dependencies and loads the extension class.
     */
    public boolean enable(String id) {
        if (loaded.containsKey(id)) {
            logger.warn("Extension already loaded: " + id);
            return true;
        }

        ExtensionDescriptor desc = descriptors.get(id);
        if (desc == null) {
            logger.error("Unknown extension: " + id);
            return false;
        }

        // Check core API compatibility
        if (!desc.version().satisfies(desc.requiredCoreApi())) {
            logger.error("Extension " + id + " requires core-api " + desc.requiredCoreApi() +
                " but running " + desc.version());
            return false;
        }

        // Resolve dependencies
        for (String depId : desc.dependencies()) {
            if (!loaded.containsKey(depId)) {
                if (!enable(depId)) {
                    logger.error("Failed to load dependency " + depId + " for " + id);
                    return false;
                }
            }
        }

        // Load extension class
        try {
            Class<?> clazz = loadExtensionClass(desc);
            Extension extension = (Extension) clazz.getDeclaredConstructor().newInstance();

            // Create context and enable
            ExtensionContext ctx = contextProvider.createContext(desc.id());
            extension.onEnable(ctx);

            loaded.put(id, new LoadedExtension(desc, extension, ctx));
            logger.info("Enabled extension: " + id);
            return true;
        } catch (Exception e) {
            logger.error("Failed to enable extension " + id + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Disables and unloads an extension.
     */
    public void disable(String id) {
        LoadedExtension le = loaded.remove(id);
        if (le == null) return;

        try {
            le.extension.onDisable();
        } catch (Exception e) {
            logger.error("Error disabling extension " + id + ": " + e.getMessage());
        }

        // Unload classloader (if using custom classloader)
        // For now, classes stay loaded but extension is disabled

        logger.info("Disabled extension: " + id);
    }

    /**
     * Reloads an extension (disable + enable).
     */
    public void reload(String id) {
        disable(id);
        enable(id);
    }

    /**
     * Reloads configuration for an extension.
     */
    public void reloadConfig(String id) {
        LoadedExtension le = loaded.get(id);
        if (le == null) return;

        ExtensionConfig config = le.context.loadConfig();
        le.extension.onConfigReload(config);
        logger.info("Reloaded config for extension: " + id);
    }

    /**
     * Returns all discovered extension descriptors.
     */
    public Collection<ExtensionDescriptor> getDescriptors() {
        return Collections.unmodifiableCollection(descriptors.values());
    }

    /**
     * Returns all loaded extensions.
     */
    public Map<String, Extension> getLoaded() {
        Map<String, Extension> result = new LinkedHashMap<>();
        for (var entry : loaded.entrySet()) {
            result.put(entry.getKey(), entry.getValue().extension);
        }
        return Collections.unmodifiableMap(result);
    }

    public boolean isLoaded(String id) {
        return loaded.containsKey(id);
    }

    public void start() {
        running = true;
        discover();
        // Auto-enable extensions marked as auto-load
        for (var desc : descriptors.values()) {
            if (desc.autoLoad()) {
                enable(desc.id());
            }
        }
        logger.info("ExtensionManager started with " + descriptors.size() + " discovered, " + loaded.size() + " loaded");
    }

    public void stop() {
        running = false;
        // Disable in reverse dependency order
        List<String> ids = new ArrayList<>(loaded.keySet());
        Collections.reverse(ids);
        for (String id : ids) {
            disable(id);
        }
        logger.info("ExtensionManager stopped");
    }

    // ============================================================
    // Internals
    // ============================================================

    private Class<?> loadExtensionClass(ExtensionDescriptor desc) throws Exception {
        // For simplicity, use system classloader.
        // Production: use custom classloader per extension for isolation.
        String className = desc.mainClass();
        return Class.forName(className, true, getClass().getClassLoader());
    }

    // ============================================================
    // Inner Classes
    // ============================================================

    private static class LoadedExtension {
        final ExtensionDescriptor descriptor;
        final Extension extension;
        final ExtensionContext context;

        LoadedExtension(ExtensionDescriptor descriptor, Extension extension, ExtensionContext context) {
            this.descriptor = descriptor;
            this.extension = extension;
            this.context = context;
        }
    }

    /**
     * Provider for ExtensionContext (allows host to inject dependencies).
     */
    public interface ExtensionContextProvider {
        ExtensionContext createContext(String extensionId);
    }

    /**
     * Extension descriptor loaded from JAR manifest.
     */
    public static final class ExtensionDescriptor {
        private final Path jarPath;
        private final String id;
        private final String name;
        private final SemVer version;
        private final SemVer requiredCoreApi;
        private final List<String> dependencies;
        private final Set<Capability> providedCapabilities;
        private final Set<Capability> requiredCapabilities;
        private final String mainClass;
        private final boolean autoLoad;

        private ExtensionDescriptor(Path jarPath, String id, String name, SemVer version,
                                    SemVer requiredCoreApi, List<String> dependencies,
                                    Set<Capability> providedCapabilities,
                                    Set<Capability> requiredCapabilities,
                                    String mainClass, boolean autoLoad) {
            this.jarPath = jarPath;
            this.id = id;
            this.name = name;
            this.version = version;
            this.requiredCoreApi = requiredCoreApi;
            this.dependencies = dependencies;
            this.providedCapabilities = providedCapabilities;
            this.requiredCapabilities = requiredCapabilities;
            this.mainClass = mainClass;
            this.autoLoad = autoLoad;
        }

        public static ExtensionDescriptor fromManifest(Path jarPath, Manifest manifest) {
            var attrs = manifest.getMainAttributes();
            String id = attrs.getValue("Extension-Id");
            String name = attrs.getValue("Extension-Name");
            String versionStr = attrs.getValue("Extension-Version");
            String coreApiStr = attrs.getValue("Extension-Required-Core-Api");
            String depsStr = attrs.getValue("Extension-Dependencies");
            String providedStr = attrs.getValue("Extension-Provides");
            String requiredStr = attrs.getValue("Extension-Requires");
            String mainClass = attrs.getValue("Extension-Main-Class");
            String autoLoadStr = attrs.getValue("Extension-Auto-Load");

            return new ExtensionDescriptor(
                jarPath,
                id != null ? id : "",
                name != null ? name : "",
                versionStr != null ? SemVer.parse(versionStr) : SemVer.of(1, 0, 0),
                coreApiStr != null ? SemVer.parse(coreApiStr) : SemVer.of(2, 1, 0),
                depsStr != null ? Arrays.asList(depsStr.split(",")) : List.of(),
                providedStr != null ? parseCapabilities(providedStr) : Set.of(),
                requiredStr != null ? parseCapabilities(requiredStr) : Set.of(),
                mainClass != null ? mainClass : "",
                autoLoadStr != null && Boolean.parseBoolean(autoLoadStr)
            );
        }

        private static Set<Capability> parseCapabilities(String str) {
            return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> {
                    String[] parts = s.split("@");
                    return Capability.of(parts[0], parts.length > 1 ? SemVer.parse(parts[1]) : SemVer.of(1, 0, 0));
                })
                .collect(Collectors.toSet());
        }

        public Path jarPath() { return jarPath; }
        public String id() { return id; }
        public String name() { return name; }
        public SemVer version() { return version; }
        public SemVer requiredCoreApi() { return requiredCoreApi; }
        public List<String> dependencies() { return dependencies; }
        public Set<Capability> providedCapabilities() { return providedCapabilities; }
        public Set<Capability> requiredCapabilities() { return requiredCapabilities; }
        public String mainClass() { return mainClass; }
        public boolean autoLoad() { return autoLoad; }
    }
}