package me.majhrs16.suite.manager.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single package entry in a manifest or status database.
 * This format is shared between METADATA/package.list and library/status.
 */
public final class PackageEntry {

    private final String name;
    private final String version;
    private final String channel;
    private final PackageType type;
    private final List<String> dependencies;
    private final String arch;
    private final String platform;
    private final String url;
    private final String os;
    private final String sha256;
    private final long size;
    private final Map<String, FileEntry> files;
    private final String sourceArtifact; // SHA256 of the distribution ZIP this package came from
    private final String parentPackage;  // For packages within a metapackage, the main package name
    private final Map<String, String> extraFields; // For status-only fields like install-time, status, etc.

    public PackageEntry(String name, String version, String channel, PackageType type,
                        List<String> dependencies, String arch, String platform,
                        String url, String os, String sha256, long size,
                        Map<String, FileEntry> files, String sourceArtifact,
                        String parentPackage, Map<String, String> extraFields) {
        this.name = name;
        this.version = version;
        this.channel = channel;
        this.type = type;
        this.dependencies = dependencies;
        this.arch = arch;
        this.platform = platform;
        this.url = url;
        this.os = os;
        this.sha256 = sha256;
        this.size = size;
        this.files = files;
        this.sourceArtifact = sourceArtifact;
        this.parentPackage = parentPackage;
        this.extraFields = extraFields;
    }

    // Getters
    public String name() { return name; }
    public String version() { return version; }
    public String channel() { return channel; }
    public PackageType type() { return type; }
    public List<String> dependencies() { return dependencies; }
    public String arch() { return arch; }
    public String platform() { return platform; }
    public String url() { return url; }
    public String os() { return os; }
    public String sha256() { return sha256; }
    public long size() { return size; }
    public Map<String, FileEntry> files() { return files; }
    public String sourceArtifact() { return sourceArtifact; }
    public String parentPackage() { return parentPackage; }
    public Map<String, String> extraFields() { return extraFields; }

    /**
     * Returns a unique identifier for this package entry.
     */
    public String id() {
        return name + "_" + version + "_" + channel;
    }

    /**
     * Checks if this entry represents a metapackage (contains other packages).
     */
    public boolean isMetapackage() {
        return type == PackageType.METAPACKAGE;
    }

    @Override
    public String toString() {
        return "PackageEntry{" +
                "name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", channel='" + channel + '\'' +
                ", type=" + type +
                ", arch='" + arch + '\'' +
                ", platform='" + platform + '\'' +
                '}';
    }
}