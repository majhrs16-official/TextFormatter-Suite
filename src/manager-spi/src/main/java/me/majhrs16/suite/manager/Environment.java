package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.SemVer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compatibility environment for modules - bridges new package system with old ModuleLifecycle API.
 */
public final class Environment {

    private final String platform;
    private final int javaVersion;
    private final String minecraftVersion;
    private final SemVer coreApiVersion;
    private final Map<String, String> installedModules;
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

    public String platform() { return platform; }
    public int javaVersion() { return javaVersion; }
    public String minecraftVersion() { return minecraftVersion; }
    public SemVer coreApiVersion() { return coreApiVersion; }
    public Map<String, String> installedModules() { return installedModules; }
    public Set<String> activeCapabilities() { return activeCapabilities; }
    public Map<String, String> systemProperties() { return systemProperties; }

    public boolean hasModule(String moduleId, SemVer minVersion) {
        String installed = installedModules.get(moduleId);
        if (installed == null) return false;
        try {
            return SemVer.parse(installed).satisfies(">=" + minVersion);
        } catch (Exception e) {
            return false;
        }
    }

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