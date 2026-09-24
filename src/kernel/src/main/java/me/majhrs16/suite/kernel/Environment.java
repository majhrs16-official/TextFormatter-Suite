package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.SemVer;

/**
 * Describes the runtime host a module is being loaded onto: the running JVM
 * and the {@code *-api} contract version this kernel was built against.
 * These become two native capabilities ( {@code jvm} and {@code api} ) that
 * modules can require.
 */
public final class Environment {

    private final SemVer contractVersion;
    private final SemVer jvmVersion;

    public Environment(SemVer contractVersion, SemVer jvmVersion) {
        this.contractVersion = contractVersion;
        this.jvmVersion = jvmVersion;
    }

    public static Environment current() {
        String version = System.getProperty("java.version");
        int major = versionMajor(version);
        return new Environment(
                SemVer.parse("2.1.0"),
                SemVer.of(major, 0, 0));
    }

    private static int versionMajor(String version) {
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2, version.indexOf('.')));
        }
        int dot = version.indexOf('.');
        return dot < 0 ? Integer.parseInt(version) : Integer.parseInt(version.substring(0, dot));
    }

    public SemVer contractVersion() {
        return contractVersion;
    }

    public SemVer jvmVersion() {
        return jvmVersion;
    }

    /** Native capability offered to every module: the running JVM version. */
    public Capability jvmCapability() {
        return Capability.of("jvm", jvmVersion);
    }

    /** Native capability offered to every module: this host's api contract. */
    public Capability apiCapability() {
        return Capability.of("api", contractVersion);
    }
}