package me.majhrs16.suite.manager;

import me.majhrs16.suite.api.SemVer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates for identifying a module in the manager system.
 * Immutable, used for resolution, download, and dependency tracking.
 */
public final class ModuleCoordinate {

    private final String group;       // e.g. "me.majhrs16"
    private final String artifact;    // e.g. "suite-textformatter"
    private final SemVer version;     // e.g. "2.1.0"
    private final String classifier;  // e.g. "shaded", "thin", null

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

    /**
     * @return Maven-style coordinate string (group:artifact:version[:classifier])
     */
    public String toCoordinateString() {
        StringBuilder sb = new StringBuilder();
        sb.append(group).append(':').append(artifact).append(':').append(version.toString());
        if (classifier != null && !classifier.isBlank()) {
            sb.append(':').append(classifier);
        }
        return sb.toString();
    }

    /**
     * @return Coordinate without classifier (for dependency resolution)
     */
    public ModuleCoordinate withoutClassifier() {
        return classifier == null ? this : new ModuleCoordinate(group, artifact, version, null);
    }

    /**
     * @return GitHub release asset name pattern (e.g. suite-textformatter-2.1.0.jar)
     */
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