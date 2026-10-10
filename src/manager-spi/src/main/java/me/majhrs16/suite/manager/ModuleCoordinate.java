package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.SemVer;

/**
 * Compatibility coordinate for modules - bridges new package system with old ModuleLifecycle API.
 */
public final class ModuleCoordinate {

    private final String group;
    private final String artifact;
    private final SemVer version;
    private final String classifier;

    public ModuleCoordinate(String group, String artifact, SemVer version, String classifier) {
        this.group = group;
        this.artifact = artifact;
        this.version = version;
        this.classifier = classifier;
    }

    public static ModuleCoordinate of(String group, String artifact, String version) {
        return new ModuleCoordinate(group, artifact, SemVer.parse(version), null);
    }

    public static ModuleCoordinate of(String group, String artifact, String version, String classifier) {
        return new ModuleCoordinate(group, artifact, SemVer.parse(version), classifier);
    }

    public String group() { return group; }
    public String artifact() { return artifact; }
    public SemVer version() { return version; }
    public String classifier() { return classifier; }

    public String toCoordinateString() {
        StringBuilder sb = new StringBuilder();
        sb.append(group).append(':').append(artifact).append(':').append(version.toString());
        if (classifier != null && !classifier.isBlank()) {
            sb.append(':').append(classifier);
        }
        return sb.toString();
    }

    public ModuleCoordinate withoutClassifier() {
        return classifier == null ? this : new ModuleCoordinate(group, artifact, version, null);
    }

    public String releaseAssetName() {
        StringBuilder sb = new StringBuilder();
        sb.append(artifact).append('-').append(version.toString());
        if (classifier != null && !classifier.isBlank()) {
            sb.append('-').append(classifier);
        }
        sb.append(".jar");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ModuleCoordinate)) return false;
        ModuleCoordinate other = (ModuleCoordinate) obj;
        return group.equals(other.group) &&
               artifact.equals(other.artifact) &&
               version.equals(other.version) &&
               (classifier == null ? other.classifier == null : classifier.equals(other.classifier));
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(group, artifact, version, classifier);
    }

    @Override
    public String toString() {
        return toCoordinateString();
    }
}