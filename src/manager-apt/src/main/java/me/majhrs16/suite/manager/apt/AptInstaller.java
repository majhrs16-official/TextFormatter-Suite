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
     */
    public InstallResult install(Collection<String> packageNames, String versionConstraint, String channel, boolean force) {
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
        
        // Execute plan
        List<String> installed = new ArrayList<>();
        for (DependencyResolver.PlanStep step : plan.steps()) {
            if (step.type() != DependencyResolver.PlanStep.Type.INSTALL) continue;
            
            RepositoryManager.PackageCandidate candidate = step.candidate();
            try {
                Path zipPath = downloadPackage(candidate);
                List<me.majhrs16.suite.manager.spi.PackageEntry> entries = installer.install(zipPath, force);
                for (me.majhrs16.suite.manager.spi.PackageEntry entry : entries) {
                    installed.add(entry.name() + " " + entry.version());
                }
            } catch (Exception e) {
                return new InstallResult(false, installed, "Failed to install " + candidate.name() + ": " + e.getMessage());
            }
        }
        
        return new InstallResult(true, installed, null);
    }

    /**
     * Upgrades all installed packages (equivalent to `apt upgrade`).
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
                Path zipPath = downloadPackage(candidate);
                List<me.majhrs16.suite.manager.spi.PackageEntry> entries = installer.install(zipPath, true); // force for upgrade
                for (me.majhrs16.suite.manager.spi.PackageEntry entry : entries) {
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
        String url = candidate.downloadUrl();
        String fileName = candidate.name() + "-" + candidate.version() + ".jar";
        Path dest = tmpDir.resolve(fileName);
        
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
            
            try (var in = response.body().byteStream();
                 var out = Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
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

        public InstallResult(boolean success, List<String> packages, String error) {
            this.success = success;
            this.packages = packages;
            this.error = error;
        }

        public boolean success() { return success; }
        public List<String> packages() { return packages; }
        public String error() { return error; }
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