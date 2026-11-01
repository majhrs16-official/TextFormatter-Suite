package me.majhrs16.suite.manager.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;

/**
 * Defines the versioned on-disk layout of the module store.
 *
 * <p>Module JARs are never overwritten while a classloader may still use them.
 * A package version always lives under its own directory and
 * {@code library/status} is the authoritative pointer to the installed version.
 * Non-JAR files remain explicitly allowed so packages can still ship
 * configuration, but they cannot target the manager's control directories.</p>
 */
final class PackageLayout {

    private PackageLayout() {
    }

    static String normalized(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("Empty file path in package manifest");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            throw new IOException("Invalid package path: " + path);
        }
        Path candidate = Path.of(normalized).normalize();
        if (candidate.isAbsolute() || candidate.startsWith("..")) {
            throw new IOException("Path traversal in package manifest: " + path);
        }
        String result = candidate.toString().replace('\\', '/');
        if (result.isBlank() || result.equals(".")) {
            throw new IOException("Invalid package path: " + path);
        }
        return result;
    }

    static String versionedPrefix(PackageEntry entry) throws IOException {
        if (!isSafeComponent(entry.name()) || !isSafeComponent(entry.version())) {
            throw new IOException("Invalid package name or version: "
                    + entry.name() + " " + entry.version());
        }
        String root;
        if (entry.type() == PackageType.DEPENDENCY) {
            root = "manager/library/dependencies/";
        } else {
            root = "manager/library/packages/";
        }
        return root + entry.name() + "/" + entry.version() + "/";
    }

    static void validate(PackageEntry entry, String filePath) throws IOException {
        String path = normalized(filePath);
        String control = path.toLowerCase(Locale.ROOT);
        if (isJar(path)) {
            if (entry.type() != PackageType.PACKAGE && entry.type() != PackageType.DEPENDENCY) {
                throw new IOException("Package type " + entry.type()
                        + " cannot install a module JAR: " + path);
            }
            String prefix = versionedPrefix(entry);
            if (!path.startsWith(prefix) || path.equals(prefix)) {
                throw new IOException("Module JAR must live under " + prefix
                        + " but manifest declares " + path);
            }
        }
        if (under(control, "manager/library/status")
                || under(control, "manager/library/journal")
                || under(control, "manager/library/backup")
                || under(control, "manager/library/trash")
                || under(control, "manager/library/cache")
                || under(control, "manager/library/lists")
                || under(control, "manager/library/tmp")
                || under(control, "manager/runtime")
                || under(control, "manager/repo.yml")) {
            throw new IOException("Package file targets a manager control path: " + path);
        }
        if (under(control, "manager/library/packages")
                || under(control, "manager/library/dependencies")) {
            if (!isJar(path)) {
                throw new IOException("Package file targets a manager control path: " + path);
            }
        } else if (under(control, "manager/library")) {
            throw new IOException("Package file targets a manager control path: " + path);
        }
    }

    static Path resolve(Path installRoot, String path) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(normalized(path)).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("Package path escapes install root: " + path);
        }
        return resolved;
    }

    static Path versionRoot(Path installRoot, PackageEntry entry) throws IOException {
        String prefix = versionedPrefix(entry);
        return resolve(installRoot, prefix.substring(0, prefix.length() - 1));
    }

    static boolean isJar(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static boolean isSafeComponent(String value) {
        return value != null && !value.isBlank()
                && !value.equals(".") && !value.equals("..")
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && value.indexOf('\0') < 0;
    }
}
