package me.majhrs16.suite.api;

/**
 * A versioned requirement on a named capability. A requirement is satisfied
 * by a provided capability with the same name whose version lies in
 * {@code [min, maxExclusive)}.
 *
 * @param name         the capability name this module needs.
 * @param min          minimum accepted version, inclusive.
 * @param maxExclusive upper bound, exclusive; {@code null} means unbounded.
 */
public record Requirement(String name, SemVer min, SemVer maxExclusive) {

    public Requirement {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("requirement name must not be blank");
        }
        if (min == null) {
            throw new IllegalArgumentException("requirement min must not be null");
        }
    }

    public boolean satisfiedBy(SemVer version) {
        if (version.compareTo(min) < 0) {
            return false;
        }
        return maxExclusive == null || version.compareTo(maxExclusive) < 0;
    }

    public static Requirement atLeast(String name, SemVer min) {
        return new Requirement(name, min, null);
    }

    public static Requirement between(String name, SemVer min, SemVer maxExclusive) {
        return new Requirement(name, min, maxExclusive);
    }

    @Override
    public String toString() {
        return name + ">=" + min + (maxExclusive == null ? "" : ",<" + maxExclusive);
    }
}