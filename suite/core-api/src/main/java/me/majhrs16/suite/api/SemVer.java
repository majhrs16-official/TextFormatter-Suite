package me.majhrs16.suite.api;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemVer implements Comparable<SemVer> {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;

    private SemVer(int major, int minor, int patch, String preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public String preRelease() {
        return preRelease;
    }

    public static SemVer of(int major, int minor, int patch) {
        return new SemVer(major, minor, patch, null);
    }

    public static SemVer parse(String raw) {
        Matcher m = PATTERN.matcher(raw == null ? "" : raw.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: '" + raw + "'");
        }
        return new SemVer(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                m.group(4)
        );
    }

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        return PATTERN.matcher(raw.trim()).matches();
    }

    @Override
    public int compareTo(SemVer other) {
        Objects.requireNonNull(other, "other");
        int byNumeric = Integer.compare(major, other.major);
        if (byNumeric != 0) {
            return byNumeric;
        }
        byNumeric = Integer.compare(minor, other.minor);
        if (byNumeric != 0) {
            return byNumeric;
        }
        byNumeric = Integer.compare(patch, other.patch);
        if (byNumeric != 0) {
            return byNumeric;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        String[] aIds = a.split("\\.");
        String[] bIds = b.split("\\.");
        int length = Math.max(aIds.length, bIds.length);
        for (int i = 0; i < length; i++) {
            boolean aHas = i < aIds.length;
            boolean bHas = i < bIds.length;
            if (!aHas) {
                return -1;
            }
            if (!bHas) {
                return 1;
            }
            int result = compareIdentifier(aIds[i], bIds[i]);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNumeric = isNumeric(a);
        boolean bNumeric = isNumeric(b);
        if (aNumeric && bNumeric) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        if (aNumeric) {
            return -1;
        }
        if (bNumeric) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean isAtLeast(int major, int minor, int patch) {
        return compareTo(new SemVer(major, minor, patch, null)) >= 0;
    }

    /**
     * Checks if this version satisfies a version range specification.
     * Supports:
     * - Exact version: "1.0.0"
     * - Range: "[1.0.0,2.0.0)", "(1.0.0,2.0.0]", "[1.0.0,2.0.0]"
     * - Caret: "^1.0.0" (>=1.0.0 <2.0.0)
     * - Tilde: "~1.0.0" (>=1.0.0 <1.1.0)
     * - Wildcard: "1.x", "1.0.x"
     * - Comparison: ">=1.0.0", "<2.0.0", ">1.0.0", "<=2.0.0"
     */
    public boolean satisfies(String rangeSpec) {
        if (rangeSpec == null || rangeSpec.isBlank()) {
            return true; // Empty spec matches everything
        }
        String spec = rangeSpec.trim();
        
        // Exact version
        if (SemVer.isValid(spec)) {
            return this.equals(SemVer.parse(spec));
        }
        
        // Wildcard: 1.x or 1.0.x
        if (spec.endsWith(".x") || spec.endsWith(".X")) {
            String base = spec.substring(0, spec.length() - 2);
            String[] parts = base.split("\\.");
            if (parts.length == 1) {
                // 1.x -> major == 1
                return this.major == Integer.parseInt(parts[0]);
            } else if (parts.length == 2) {
                // 1.0.x -> major == 1 && minor == 0
                return this.major == Integer.parseInt(parts[0]) 
                    && this.minor == Integer.parseInt(parts[1]);
            }
            return false;
        }
        
        // Caret range: ^1.0.0
        if (spec.startsWith("^")) {
            SemVer base = SemVer.parse(spec.substring(1));
            if (base.major() == 0) {
                // 0.x.x -> only patch changes allowed
                return this.major == 0 && this.minor == base.minor() 
                    && this.patch >= base.patch();
            }
            // ^1.0.0 -> >=1.0.0 <2.0.0
            return this.compareTo(base) >= 0 
                && this.major < base.major() + 1;
        }
        
        // Tilde range: ~1.0.0
        if (spec.startsWith("~")) {
            SemVer base = SemVer.parse(spec.substring(1));
            // ~1.0.0 -> >=1.0.0 <1.1.0
            return this.compareTo(base) >= 0 
                && (this.major == base.major() && this.minor == base.minor());
        }
        
        // Comparison operators
        if (spec.startsWith(">=")) {
            SemVer min = SemVer.parse(spec.substring(2));
            return this.compareTo(min) >= 0;
        }
        if (spec.startsWith("<=")) {
            SemVer max = SemVer.parse(spec.substring(2));
            return this.compareTo(max) <= 0;
        }
        if (spec.startsWith(">")) {
            SemVer min = SemVer.parse(spec.substring(1));
            return this.compareTo(min) > 0;
        }
        if (spec.startsWith("<")) {
            SemVer max = SemVer.parse(spec.substring(1));
            return this.compareTo(max) < 0;
        }
        
        // Range syntax: [1.0.0,2.0.0), (1.0.0,2.0.0], etc.
        if ((spec.startsWith("[") || spec.startsWith("(")) 
                && (spec.endsWith("]") || spec.endsWith(")"))) {
            return satisfiesRange(spec);
        }
        
        return false;
    }
    
    private boolean satisfiesRange(String spec) {
        // Format: [min,max], [min,max), (min,max], (min,max)
        boolean minInclusive = spec.startsWith("[");
        boolean maxInclusive = spec.endsWith("]");
        
        // Remove brackets and split by comma
        String inner = spec.substring(1, spec.length() - 1);
        String[] parts = inner.split(",", 2);
        if (parts.length != 2) {
            return false;
        }
        
        String minStr = parts[0].trim();
        String maxStr = parts[1].trim();
        
        boolean minOk;
        if (minStr.isEmpty()) {
            minOk = true; // No lower bound
        } else {
            SemVer min = SemVer.parse(minStr);
            minOk = minInclusive ? this.compareTo(min) >= 0 : this.compareTo(min) > 0;
        }
        
        boolean maxOk;
        if (maxStr.isEmpty()) {
            maxOk = true; // No upper bound
        } else {
            SemVer max = SemVer.parse(maxStr);
            maxOk = maxInclusive ? this.compareTo(max) <= 0 : this.compareTo(max) < 0;
        }
        
        return minOk && maxOk;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch;
        return preRelease == null ? base : base + "-" + preRelease;
    }
}