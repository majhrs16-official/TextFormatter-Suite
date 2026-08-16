package me.majhrs16.suite.api;

/**
 * A named, versioned capability a module can provide to, or require from,
 * other modules. Capabilities are the currency of the dependency graph.
 *
 * @param name    a stable, unique capability name (e.g. {@code translator}).
 * @param version the semantic version of the capability contract.
 */
public record Capability(String name, SemVer version) {

    public Capability {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("capability name must not be blank");
        }
        if (version == null) {
            throw new IllegalArgumentException("capability version must not be null");
        }
    }

    public static Capability of(String name, SemVer version) {
        return new Capability(name, version);
    }

    @Override
    public String toString() {
        return name + '@' + version;
    }
}