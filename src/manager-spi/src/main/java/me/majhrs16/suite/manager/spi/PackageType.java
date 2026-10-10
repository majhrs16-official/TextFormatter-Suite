package me.majhrs16.suite.manager.spi;

/**
 * Type of package in the manager system.
 */
public enum PackageType {
    /**
     * Regular installable package.
     */
    PACKAGE,

    /**
     * Shared dependency package (can be shared by multiple packages).
     */
    DEPENDENCY,

    /**
     * Metapackage that groups multiple packages in a single distribution artifact.
     */
    METAPACKAGE
}