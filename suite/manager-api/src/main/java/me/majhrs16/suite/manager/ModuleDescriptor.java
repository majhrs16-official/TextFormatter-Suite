package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.SemVer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Descriptor for a module in the manager system.
 * Contains metadata, dependencies, capabilities, and compatibility info.
 * Loaded from the module's JAR manifest or module.yml.
 */
public final class ModuleDescriptor {

    private final ModuleCoordinate coordinate;
    private final String name;
    private final String description;
    private final String author;
    private final String website;
    private final String license;
    private final SemVer requiredCoreApi;
    private final String minJavaVersion;
    private final String maxJavaVersion;
    private final List<String> supportedPlatforms;  // "spigot", "fabric", "velocity", "bungee", "common"
    private final List<String> dependencies;       // ModuleCoordinate strings
    private final Set<Capability> provides;
    private final Set<Capability> requires;
    private final Map<String, String> properties;  // arbitrary key-value

    public ModuleDescriptor(ModuleCoordinate coordinate,
                            String name,
                            String description,
                            String author,
                            String website,
                            String license,
                            SemVer requiredCoreApi,
                            String minJavaVersion,
                            String maxJavaVersion,
                            List<String> supportedPlatforms,
                            List<String> dependencies,
                            Set<Capability> provides,
                            Set<Capability> requires,
                            Map<String, String> properties) {
        this.coordinate = coordinate;
        this.name = name;
        this.description = description;
        this.author = author;
        this.website = website;
        this.license = license;
        this.requiredCoreApi = requiredCoreApi;
        this.minJavaVersion = minJavaVersion;
        this.maxJavaVersion = maxJavaVersion;
        this.supportedPlatforms = supportedPlatforms;
        this.dependencies = dependencies;
        this.provides = provides;
        this.requires = requires;
        this.properties = properties;
    }

    // Getters
    public ModuleCoordinate coordinate() { return coordinate; }
    public String name() { return name; }
    public String description() { return description; }
    public String author() { return author; }
    public String website() { return website; }
    public String license() { return license; }
    public SemVer requiredCoreApi() { return requiredCoreApi; }
    public String minJavaVersion() { return minJavaVersion; }
    public String maxJavaVersion() { return maxJavaVersion; }
    public List<String> supportedPlatforms() { return supportedPlatforms; }
    public List<String> dependencies() { return dependencies; }
    public Set<Capability> provides() { return provides; }
    public Set<Capability> requires() { return requires; }
    public Map<String, String> properties() { return properties; }

    /**
     * Checks if this module is compatible with the current environment.
     */
    public boolean isCompatible(Environment env) {
        // Check Java version
        if (minJavaVersion != null && !minJavaVersion.isBlank()) {
            int minJava = Integer.parseInt(minJavaVersion);
            if (env.javaVersion() < minJava) return false;
        }
        if (maxJavaVersion != null && !maxJavaVersion.isBlank()) {
            int maxJava = Integer.parseInt(maxJavaVersion);
            if (env.javaVersion() > maxJava) return false;
        }

        // Check platform
        if (supportedPlatforms != null && !supportedPlatforms.isEmpty()) {
            if (!supportedPlatforms.contains(env.platform())) return false;
        }

        return true;
    }

    /**
     * Checks if this module version satisfies a version range.
     */
    public boolean satisfies(SemVer range) {
        return version().satisfies(range);
    }

    public SemVer version() { return coordinate().version(); }
    public String id() { return coordinate().toCoordinateString(); }

    @Override
    public String toString() {
        return name + " " + version();
    }
}