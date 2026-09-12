package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.SemVer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the runtime environment for module resolution and compatibility.
 * Immutable snapshot of the current platform, Java version, and installed modules.
 */
public final class Environment {

    private final String platform;           // "spigot", "fabric", "velocity", "bungee", "common"
    private final int javaVersion;           // e.g. 17, 21
    private final String minecraftVersion;   // e.g. "1.20.6", "1.21"
    private final SemVer coreApiVersion;     // core-api version
    private final Map<String, String> installedModules;  // id -> version
    private final Set<String> activeCapabilities;
    private final Map<String, String> systemProperties;

    public Environment(String platform,
                       int javaVersion,
                       String minecraftVersion,
                       SemVer coreApiVersion,
                       Map<String, String> installedModules,
                       Set<String> activeCapabilities,
                       Map<String, String> systemProperties) {
        this.platform = platform;
        this.javaVersion = javaVersion;
        this.minecraftVersion = minecraftVersion;
        this.coreApiVersion = coreApiVersion;
        this.installedModules = installedModules;
        this.activeCapabilities = activeCapabilities;
        this.systemProperties = systemProperties;
    }

    // Getters
    public String platform() { return platform; }
    public int javaVersion() { return javaVersion; }
    public String minecraftVersion() { return minecraftVersion; }
    public SemVer coreApiVersion() { return coreApiVersion; }
    public Map<String, String> installedModules() { return installedModules; }
    public Set<String> activeCapabilities() { return activeCapabilities; }
    public Map<String, String> systemProperties() { return systemProperties; }

    /**
     * Checks if a module is installed at a specific version or higher.
     */
    public boolean hasModule(String moduleId, SemVer minVersion) {
        String installed = installedModules.get(moduleId);
        if (installed == null) return false;
        try {
            return SemVer.parse(installed).satisfies(minVersion);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a capability is available.
     */
    public boolean hasCapability(String capability) {
        return activeCapabilities.contains(capability);
    }

    @Override
    public String toString() {
        return "Environment{" +
                "platform='" + platform + '\'' +
                ", javaVersion=" + javaVersion +
                ", minecraftVersion='" + minecraftVersion + '\'' +
                ", coreApiVersion=" + coreApiVersion +
                ", installedModules=" + installedModules.size() +
                '}';
    }
}