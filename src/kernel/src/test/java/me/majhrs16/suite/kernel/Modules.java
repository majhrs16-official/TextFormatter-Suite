package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

import java.util.function.Function;

final class Modules {

    private Modules() {
    }

    static Module simple(String name, int contractMajor) {
        return named(name, SemVer.of(1, 0, 0), contractMajor, d -> d);
    }

    static Module named(String name, SemVer version, int contractMajor,
                        Function<ModuleDescriptor.Builder, ModuleDescriptor.Builder> tweak) {
        ModuleDescriptor.Builder base = ModuleDescriptor.builder(name)
                .version(version)
                .contractVersion(SemVer.of(contractMajor, 0, 0));
        ModuleDescriptor descriptor = tweak.apply(base).build();
        return () -> descriptor;
    }

    static Module providing(String name, int contractMajor, Capability capability) {
        return named(name, SemVer.of(1, 0, 0), contractMajor,
                d -> d.provide(capability));
    }

    static Module requiring(String name, int contractMajor, Requirement requirement) {
        return named(name, SemVer.of(1, 0, 0), contractMajor,
                d -> d.require(requirement));
    }

    /** Module that requires one capability while providing another. */
    static Module bothWays(String name, String requiresName, String providesName) {
        return named(name, SemVer.of(1, 0, 0), 3,
                d -> d
                        .require(Requirement.atLeast(requiresName, SemVer.of(1, 0, 0)))
                        .provide(Capability.of(providesName, SemVer.of(1, 0, 0))));
    }
}