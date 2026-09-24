package me.majhrs16.suite.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable description of a suite module: what it is, against which
 * contract it was compiled, what it provides and what it needs.
 *
 * @param name            unique module id, e.g. {@code textformatter}.
 * @param version         the module's own version.
 * @param contractVersion the {@code *-api} contract version the module was
 *                        compiled against (handshake).
 * @param jvmMin          minimum JVM major version, inclusive.
 * @param jvmMax          maximum JVM major version, inclusive; {@code 0}
 *                        means unbounded.
 * @param provides        capabilities this module offers to the graph.
 * @param requires        capabilities this module needs to activate.
 */
public record ModuleDescriptor(
        String name,
        SemVer version,
        SemVer contractVersion,
        int jvmMin,
        int jvmMax,
        Set<Capability> provides,
        Set<Requirement> requires
) {

    public ModuleDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("module name must not be blank");
        }
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(contractVersion, "contractVersion");
        provides = provides == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(provides));
        requires = requires == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(requires));
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private SemVer version;
        private SemVer contractVersion;
        private int jvmMin;
        private int jvmMax;
        private final Set<Capability> provides = new LinkedHashSet<>();
        private final Set<Requirement> requires = new LinkedHashSet<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder version(SemVer version) {
            this.version = version;
            return this;
        }

        public Builder contractVersion(SemVer contractVersion) {
            this.contractVersion = contractVersion;
            return this;
        }

        public Builder jvmRange(int min, int max) {
            this.jvmMin = min;
            this.jvmMax = max;
            return this;
        }

        public Builder provide(Capability capability) {
            this.provides.add(capability);
            return this;
        }

        public Builder require(Requirement requirement) {
            this.requires.add(requirement);
            return this;
        }

        public ModuleDescriptor build() {
            return new ModuleDescriptor(name, version, contractVersion, jvmMin, jvmMax, provides, requires);
        }
    }
}