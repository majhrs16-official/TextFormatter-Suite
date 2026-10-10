package me.majhrs16.suite.manager.spi;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version implementation for package version comparison.
 * Supports: major.minor.patch[-prerelease][+build]
 * Comparison follows semver 2.0.0 specification.
 */
public final class Version implements Comparable<Version> {

    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
    );

    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;
    private final String build;
    private final String original;

    private Version(int major, int minor, int patch, String prerelease, String build, String original) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.build = build;
        this.original = original;
    }

    /**
     * Parses a version string.
     */
    public static Version parse(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Version string cannot be empty");
        }
        String trimmed = version.strip();
        Matcher matcher = SEMVER_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + version);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        String prerelease = matcher.group(4);
        String build = matcher.group(5);
        return new Version(major, minor, patch, prerelease, build, trimmed);
    }

    /**
     * Parses a version string, returning null if invalid.
     */
    public static Version tryParse(String version) {
        try {
            return parse(version);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public int major() { return major; }
    public int minor() { return minor; }
    public int patch() { return patch; }
    public String prerelease() { return prerelease; }
    public String build() { return build; }
    public String original() { return original; }

    @Override
    public int compareTo(Version other) {
        // Compare major, minor, patch
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.minor, other.minor);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.patch, other.patch);
        if (cmp != 0) return cmp;

        // Compare prerelease
        // Per semver: prerelease versions have lower precedence than release versions
        if (this.prerelease == null && other.prerelease == null) return 0;
        if (this.prerelease == null) return 1;  // release > prerelease
        if (other.prerelease == null) return -1;

        // Compare prerelease identifiers
        String[] thisParts = this.prerelease.split("\\.");
        String[] otherParts = other.prerelease.split("\\.");
        int minLen = Math.min(thisParts.length, otherParts.length);
        for (int i = 0; i < minLen; i++) {
            cmp = compareIdentifiers(thisParts[i], otherParts[i]);
            if (cmp != 0) return cmp;
        }
        // If all compared identifiers are equal, the one with more identifiers wins
        return Integer.compare(thisParts.length, otherParts.length);
    }

    private int compareIdentifiers(String a, String b) {
        boolean aNum = a.matches("\\d+");
        boolean bNum = b.matches("\\d+");
        if (aNum && bNum) {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        }
        if (aNum) return -1;  // numeric < alphanumeric
        if (bNum) return 1;
        return a.compareTo(b);  // lexicographic for alphanumeric
    }

    /**
     * Checks if this version satisfies a range specification.
     * Supports: exact, ^ (caret), ~ (tilde), >=, <=, >, <, !=, || (or), - (range)
     */
    public boolean satisfies(String rangeSpec) {
        if (rangeSpec == null || rangeSpec.isBlank()) return true;
        
        String spec = rangeSpec.strip();
        
        // Handle OR (||) - any matching range satisfies
        if (spec.contains("||")) {
            for (String part : spec.split("\\|\\|")) {
                if (satisfies(part.strip())) return true;
            }
            return false;
        }

        // Handle range with hyphen (e.g., "1.0.0 - 2.0.0")
        if (spec.contains(" - ")) {
            String[] parts = spec.split(" - ");
            if (parts.length == 2) {
                Version min = parse(parts[0].strip());
                Version max = parse(parts[1].strip());
                return this.compareTo(min) >= 0 && this.compareTo(max) <= 0;
            }
        }

        // Handle operators
        if (spec.startsWith(">=")) {
            return this.compareTo(parse(spec.substring(2).strip())) >= 0;
        }
        if (spec.startsWith("<=")) {
            return this.compareTo(parse(spec.substring(2).strip())) <= 0;
        }
        if (spec.startsWith(">")) {
            return this.compareTo(parse(spec.substring(1).strip())) > 0;
        }
        if (spec.startsWith("<")) {
            return this.compareTo(parse(spec.substring(1).strip())) < 0;
        }
        if (spec.startsWith("!=")) {
            return this.compareTo(parse(spec.substring(2).strip())) != 0;
        }
        if (spec.startsWith("^")) {
            // Caret: >= version, < next major (or minor if 0.x)
            Version base = parse(spec.substring(1).strip());
            if (base.major == 0) {
                // 0.x.y -> >= 0.x.y, < 0.(x+1).0
                Version next = new Version(0, base.minor + 1, 0, null, null, "");
                return this.compareTo(base) >= 0 && this.compareTo(next) < 0;
            } else {
                // x.y.z -> >= x.y.z, < (x+1).0.0
                Version next = new Version(base.major + 1, 0, 0, null, null, "");
                return this.compareTo(base) >= 0 && this.compareTo(next) < 0;
            }
        }
        if (spec.startsWith("~")) {
            // Tilde: >= version, < next minor
            Version base = parse(spec.substring(1).strip());
            Version next = new Version(base.major, base.minor + 1, 0, null, null, "");
            return this.compareTo(base) >= 0 && this.compareTo(next) < 0;
        }

        // Exact match or wildcard
        if (spec.endsWith("*") || spec.endsWith("x") || spec.endsWith("X")) {
            // Handle wildcards like 1.2.* or 1.x
            return matchesWildcard(spec);
        }

        // Default: exact match
        return this.compareTo(parse(spec)) == 0;
    }

    private boolean matchesWildcard(String pattern) {
        // Convert wildcard pattern to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", "\\d+")
                .replace("X", "\\d+")
                .replace("x", "\\d+");
        return this.original.matches(regex);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Version)) return false;
        Version other = (Version) obj;
        return major == other.major && minor == other.minor && patch == other.patch &&
                Objects.equals(prerelease, other.prerelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }

    @Override
    public String toString() {
        return original;
    }
}