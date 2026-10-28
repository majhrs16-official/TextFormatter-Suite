package me.majhrs16.suite.api.runtime;

import java.util.Objects;

/**
 * Neutral description of the hosting platform.
 *
 * @param name       platform name, e.g. {@code spigot}, {@code fabric} or
 *                   {@code test}
 * @param version    platform version, e.g. {@code 1.21.1}
 * @param arch       CPU architecture, e.g. {@code x86_64}
 * @param os         operating system name, e.g. {@code linux}
 * @param javaMajor  Java major version of the running JVM
 */
public record PlatformInfo(String name, String version, String arch, String os, int javaMajor) {

    public PlatformInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("platform name must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("platform version must not be blank");
        }
        if (arch == null || arch.isBlank()) {
            throw new IllegalArgumentException("platform arch must not be blank");
        }
        if (os == null || os.isBlank()) {
            throw new IllegalArgumentException("platform os must not be blank");
        }
        if (javaMajor < 1) {
            throw new IllegalArgumentException("javaMajor must be positive");
        }
    }

    /** @return a neutral description of the current JVM/platform. */
    public static PlatformInfo current(String platformName, String platformVersion) {
        return new PlatformInfo(
                Objects.requireNonNull(platformName, "platformName"),
                Objects.requireNonNull(platformVersion, "platformVersion"),
                System.getProperty("os.arch", "unknown"),
                System.getProperty("os.name", "unknown"),
                Runtime.version().feature());
    }
}
