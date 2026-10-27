package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.RepositoryConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * APT installer - downloads packages from repositories and delegates to dpkg (PackageInstaller).
 */
public final class AptInstaller {

    /** Absolute ceiling for a package download, independent of index metadata. */
    static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;

    private final RepositoryManager repoManager;
    private final DependencyResolver resolver;
    private final PackageInstaller installer;
    private final LocalPackageDatabase database;
    private final Path tmpDir;
    private final OkHttpClient httpClient;
    private final PluginLogger logger;

    public AptInstaller(RepositoryManager repoManager, DependencyResolver resolver,
                        PackageInstaller installer, LocalPackageDatabase database, PluginLogger logger) {
        this.repoManager = repoManager;
        this.resolver = resolver;
        this.installer = installer;
        this.database = database;
        this.tmpDir = database.getTmpDir();
        this.logger = logger;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Installs packages from repositories (equivalent to `apt install`).
     * This convenience overload automatically confirms replacement plans; user
     * interfaces should call {@link #install(Collection, String, String, boolean, boolean)}
     * with {@code confirmed=false} first to show the replacement prompt.
     */
    public InstallResult install(Collection<String> packageNames, String versionConstraint, String channel, boolean force) {
        return install(packageNames, versionConstraint, channel, force, true);
    }

    /**
     * Installs packages from repositories. When the resolved plan needs to remove
     * packages because of an {@code Exclude} conflict, {@code confirmed=false}
     * returns a confirmation result instead of modifying the system.
     */
    public InstallResult install(Collection<String> packageNames, String versionConstraint,
                                 String channel, boolean force, boolean confirmed) {
        logger.info("APT install: " + packageNames + " " + versionConstraint + " (" + channel + ")");
        
        // Resolve plan
        DependencyResolver.ResolutionPlan plan;
        try {
            plan = resolver.resolveInstall(packageNames, versionConstraint, channel, force);
        } catch (DependencyResolver.ResolutionException e) {
            return new InstallResult(false, List.of(), e.getMessage());
        }
        
        if (plan.steps().isEmpty()) {
            return new InstallResult(true, List.of(), "No packages to install");
        }

        if (plan.requiresConfirmation() && !confirmed) {
            return InstallResult.confirmation(plan);
        }
        
        // Download every package before removing anything from the system. This
        // avoids losing the replaced package when the network fails halfway.
        List<DownloadedPackage> downloads = new ArrayList<>();
        for (DependencyResolver.PlanStep step : plan.steps()) {
            if (step.type() != DependencyResolver.PlanStep.Type.INSTALL) continue;
            RepositoryManager.PackageCandidate candidate = step.candidate();
            try {
                downloads.add(new DownloadedPackage(candidate, downloadPackage(candidate)));
            } catch (Exception e) {
                return new InstallResult(false, List.of(),
                        "Failed to download " + candidate.name() + ": " + e.getMessage());
            }
        }

        // Execute plan: removed packages must leave before the new ones are installed.
        List<String> installed = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        for (me.majhrs16.suite.manager.spi.PackageEntry oldPackage : plan.replaced()) {
            try {
                installer.remove(oldPackage.name(), oldPackage.version(), oldPackage.channel(), false);
                replaced.add(oldPackage.name() + " " + oldPackage.version());
            } catch (Exception e) {
                return new InstallResult(false, installed,
                        "Failed to replace " + oldPackage.name() + ": " + e.getMessage());
            }
        }

        for (DownloadedPackage download : downloads) {
            RepositoryManager.PackageCandidate candidate = download.candidate();
            try {
                List<me.majhrs16.suite.manager.spi.PackageEntry> entries =
                        installer.install(download.zipPath(), force);
                for (me.majhrs16.suite.manager.spi.PackageEntry entry : entries) {
                    installed.add(entry.name() + " " + entry.version());
                }
            } catch (Exception e) {
                String prefix = replaced.isEmpty() ? "" : "Replaced " + String.join(", ", replaced) + ". ";
                return new InstallResult(false, installed,
                        prefix + "Failed to install " + candidate.name() + ": " + e.getMessage());
            }
        }
        
        return new InstallResult(true, installed, null);
    }

    private record DownloadedPackage(RepositoryManager.PackageCandidate candidate, Path zipPath) {}

    /**
     * Upgrades all installed packages (equivalent to `apt upgrade`).
     * Installs new version FIRST, then removes old version (atomic upgrade).
     */
    public InstallResult upgrade() {
        logger.info("APT upgrade");
        
        DependencyResolver.ResolutionPlan plan;
        try {
            plan = resolver.resolveUpgrade();
        } catch (DependencyResolver.ResolutionException e) {
            return new InstallResult(false, List.of(), e.getMessage());
        }
        
        if (plan.steps().isEmpty()) {
            return new InstallResult(true, List.of(), "No packages to upgrade");
        }
        
        List<String> upgraded = new ArrayList<>();
        for (DependencyResolver.PlanStep step : plan.steps()) {
            RepositoryManager.PackageCandidate candidate = step.candidate();
            try {
                // Download and install new version FIRST
                Path zipPath = downloadPackage(candidate);
                List<me.majhrs16.suite.manager.spi.PackageEntry> entries = installer.install(zipPath, true);
                
                // Only remove old version AFTER successful installation
                for (me.majhrs16.suite.manager.spi.PackageEntry entry : entries) {
                    Optional<me.majhrs16.suite.manager.spi.PackageEntry> oldEntry = 
                        database.getLatestInstalled(entry.name(), entry.channel());
                    if (oldEntry.isPresent() && !oldEntry.get().version().equals(entry.version())) {
                        logger.info("Removing old version " + oldEntry.get().version() + " of " + entry.name());
                        try {
                            installer.remove(oldEntry.get().name(), oldEntry.get().version(), oldEntry.get().channel(), false);
                        } catch (Exception e) {
                            logger.warn("Failed to remove old version of " + entry.name() + ": " + e.getMessage());
                            // Don't fail the upgrade if old version removal fails
                        }
                    }
                    upgraded.add(entry.name() + " " + entry.version());
                }
            } catch (Exception e) {
                return new InstallResult(false, upgraded, "Failed to upgrade " + candidate.name() + ": " + e.getMessage());
            }
        }
        
        return new InstallResult(true, upgraded, null);
    }

    /**
     * Fixes broken dependencies (equivalent to `apt --fix-broken install`).
     */
    public InstallResult fixBroken() {
        logger.info("APT fix-broken");
        
        DependencyResolver.ResolutionPlan plan;
        try {
            plan = resolver.resolveFixBroken();
        } catch (DependencyResolver.ResolutionException e) {
            return new InstallResult(false, List.of(), e.getMessage());
        }
        
        if (plan.steps().isEmpty()) {
            return new InstallResult(true, List.of(), "No broken dependencies to fix");
        }
        
        List<String> fixed = new ArrayList<>();
        for (DependencyResolver.PlanStep step : plan.steps()) {
            RepositoryManager.PackageCandidate candidate = step.candidate();
            try {
                Path zipPath = downloadPackage(candidate);
                List<me.majhrs16.suite.manager.spi.PackageEntry> entries = installer.install(zipPath, false);
                for (me.majhrs16.suite.manager.spi.PackageEntry entry : entries) {
                    fixed.add(entry.name() + " " + entry.version());
                }
            } catch (Exception e) {
                return new InstallResult(false, fixed, "Failed to fix " + candidate.name() + ": " + e.getMessage());
            }
        }
        
        return new InstallResult(true, fixed, null);
    }

    /**
     * Removes a package (equivalent to `apt remove`).
     */
    public RemoveResult remove(String name, String version, String channel, boolean autoRemove) {
        logger.info("APT remove: " + name + " " + version + " (" + channel + ")");
        
        try {
            installer.remove(name, version, channel, autoRemove);
            return new RemoveResult(true, name + " " + version, null);
        } catch (PackageInstaller.InstallException e) {
            return new RemoveResult(false, null, e.getMessage());
        }
    }

    /**
     * Downloads a package ZIP to the temporary directory.
     */
    private Path downloadPackage(RepositoryManager.PackageCandidate candidate) throws IOException {
        if (candidate.size() <= 0 || candidate.size() > MAX_PACKAGE_BYTES) {
            throw new IOException("Invalid package size: " + candidate.size());
        }

        String url = candidate.downloadUrl();
        String fileName = packageFileName(candidate.name(), candidate.version());
        Path dest = safeDestination(tmpDir, fileName);
        
        logger.debug("Downloading " + candidate.name() + " from " + url);
        
        // Check cache
        if (Files.exists(dest)) {
            if (verifyChecksum(dest, candidate.sha256()) && Files.size(dest) == candidate.size()) {
                logger.debug("Using cached package: " + dest);
                return dest;
            }
        }
        
        Files.createDirectories(tmpDir);
        
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            
            // MGR-018: Zip bomb protection - limit download size
            long contentLength = response.body().contentLength();
            long maxDownloadSize = candidate.size() + 1024 * 1024; // allow 1MB overhead
            if (contentLength > 0 && contentLength > maxDownloadSize) {
                throw new IOException("Download size exceeds expected size + overhead: " + contentLength + " > " + maxDownloadSize);
            }
            
            try (var in = response.body().byteStream();
                 var out = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                // Limit bytes read to prevent zip bomb
                long bytesRead = 0;
                long maxBytes = maxDownloadSize;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    bytesRead += read;
                    if (bytesRead > maxBytes) {
                        throw new IOException("Download exceeds maximum allowed size: " + maxBytes);
                    }
                    out.write(buffer, 0, read);
                }
            }
        }
        
        // Verify
        if (!verifyChecksum(dest, candidate.sha256())) {
            Files.deleteIfExists(dest);
            throw new IOException("Checksum mismatch for downloaded package");
        }
        if (Files.size(dest) != candidate.size()) {
            Files.deleteIfExists(dest);
            throw new IOException("Size mismatch for downloaded package");
        }
        
        return dest;
    }

    /**
     * Builds the cache file name, rejecting separators and traversal segments.
     */
    static String packageFileName(String name, String version) throws IOException {
        if (!isSafeComponent(name) || !isSafeComponent(version)) {
            throw new IOException("Invalid package name or version");
        }
        return name + "-" + version + ".zip";
    }

    private static boolean isSafeComponent(String value) {
        return value != null && !value.isBlank()
                && !value.equals(".") && !value.equals("..")
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && value.indexOf('\0') < 0;
    }

    private static Path safeDestination(Path baseDir, String fileName) throws IOException {
        Path base = baseDir.toAbsolutePath().normalize();
        Path destination = base.resolve(fileName).normalize();
        if (destination.equals(base) || !destination.startsWith(base)) {
            throw new IOException("Invalid package destination");
        }
        return destination;
    }

    private boolean verifyChecksum(Path file, String expectedSha256) {
        try {
            return computeSha256(file).equalsIgnoreCase(expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }

    private String computeSha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    public static class InstallResult {
        private final boolean success;
        private final List<String> packages;
        private final String error;
        private final boolean requiresConfirmation;
        private final List<String> packagesToInstall;
        private final List<String> packagesToReplace;

        public InstallResult(boolean success, List<String> packages, String error) {
            this(success, packages, error, false, List.of(), List.of());
        }

        public InstallResult(boolean success, List<String> packages, String error,
                             boolean requiresConfirmation, List<String> packagesToInstall,
                             List<String> packagesToReplace) {
            this.success = success;
            this.packages = packages;
            this.error = error;
            this.requiresConfirmation = requiresConfirmation;
            this.packagesToInstall = packagesToInstall;
            this.packagesToReplace = packagesToReplace;
        }

        static InstallResult confirmation(DependencyResolver.ResolutionPlan plan) {
            List<String> toInstall = plan.steps().stream()
                    .filter(step -> step.type() == DependencyResolver.PlanStep.Type.INSTALL)
                    .map(step -> step.candidate().name() + " " + step.candidate().version())
                    .collect(Collectors.toList());
            List<String> toReplace = plan.replaced().stream()
                    .map(entry -> entry.name() + " " + entry.version())
                    .collect(Collectors.toList());
            return new InstallResult(false, List.of(), null, true, toInstall, toReplace);
        }

        public boolean success() { return success; }
        public List<String> packages() { return packages; }
        public String error() { return error; }
        public boolean requiresConfirmation() { return requiresConfirmation; }
        public List<String> packagesToInstall() { return packagesToInstall; }
        public List<String> packagesToReplace() { return packagesToReplace; }

        /** APT-style replacement question for command user interfaces. */
        public String confirmationMessage() {
            return "Deseas instalar estos paquetes?: " + String.join(", ", packagesToInstall)
                    + "\n  Reemplazarán a: " + String.join(", ", packagesToReplace);
        }
    }

    public static class RemoveResult {
        private final boolean success;
        private final String packageName;
        private final String error;

        public RemoveResult(boolean success, String packageName, String error) {
            this.success = success;
            this.packageName = packageName;
            this.error = error;
        }

        public boolean success() { return success; }
        public String packageName() { return packageName; }
        public String error() { return error; }
    }
}