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