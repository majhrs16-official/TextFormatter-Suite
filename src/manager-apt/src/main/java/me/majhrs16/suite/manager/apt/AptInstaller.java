package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.RepositoryConfig;

import okhttp3.OkHttpClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * APT installer - downloads packages from repositories and delegates to dpkg (PackageInstaller).
 */
public final class AptInstaller {

    /** Absolute ceiling for a package download, independent of index metadata. */
    static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;

    private final DependencyResolver resolver;
    private final PackageInstaller installer;
    private final PackageCache cache;
    private final PluginLogger logger;

    public AptInstaller(DependencyResolver resolver, PackageInstaller installer,
                        LocalPackageDatabase database, PluginLogger logger) {
        this.resolver = resolver;
        this.installer = installer;
        this.logger = logger;
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.cache = new PackageCache(database.getManagerDir(), httpClient, logger);
    }

    /**
     * Installs packages from repositories (equivalent to `apt install`).
     * This convenience overload automatically confirms plans that do not need a
     * runtime barrier. A plan that replaces installed packages must use the
     * overload that supplies one; otherwise it fails before mutating files.
     */
    public InstallResult install(Collection<String> packageNames, String versionConstraint, String channel, boolean force) {
        return install(packageNames, versionConstraint, channel, force, true);
    }

    /**
     * Installs packages from repositories. When the resolved plan needs to remove
     * packages because of an {@code Exclude} conflict, {@code confirmed=false}
     * returns a confirmation result instead of modifying the system. A confirmed
     * replacement still requires the overload that supplies a runtime barrier.
     */
    public InstallResult install(Collection<String> packageNames, String versionConstraint,
                                 String channel, boolean force, boolean confirmed) {
        return install(packageNames, versionConstraint, channel, force, confirmed,
                replaced -> {
                    throw new IllegalStateException(
                            "Replacement requires a runtime barrier: " + replaced);
                });
    }

    /**
     * Installs packages from repositories, invoking {@code replacementBarrier}
     * with the installed packages that the plan removes through {@code Exclude}
     * before any file is moved.
     */
    public InstallResult install(Collection<String> packageNames, String versionConstraint,
                                 String channel, boolean force, boolean confirmed,
                                 Consumer<Collection<PackageEntry>> replacementBarrier) {
        Objects.requireNonNull(replacementBarrier, "replacementBarrier");
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
        
        // Stage 1: download and verify every artifact before touching the
        // installed store. The cache records PENDING/COMPLETE per operation.
        String operationId = UUID.randomUUID().toString();
        List<Path> downloads = new ArrayList<>();
        for (DependencyResolver.PlanStep step : plan.steps()) {
            if (step.type() != DependencyResolver.PlanStep.Type.INSTALL) continue;
            RepositoryManager.PackageCandidate candidate = step.candidate();
            try {
                downloads.add(downloadPackage(candidate, operationId));
            } catch (Exception e) {
                return new InstallResult(false, List.of(),
                        "Failed to download " + candidate.name() + ": " + e.getMessage());
            }
        }

        // Stage 2: one file/status transaction for the whole plan.
        try {
            Runnable barrier = plan.replaced().isEmpty()
                    ? () -> {
                    }
                    : () -> replacementBarrier.accept(plan.replaced());
            List<PackageEntry> entries =
                    installer.installBatch(downloads, force, plan.replaced(), barrier);
            List<String> installed = entries.stream()
                    .map(entry -> entry.name() + " " + entry.version())
                    .collect(Collectors.toList());
            return new InstallResult(true, installed, null);
        } catch (Exception e) {
            return InstallResult.failedCommit(
                    "Failed to commit package batch: " + e.getMessage(), e);
        }
    }

    /**
     * Upgrades all installed packages (equivalent to `apt upgrade`).
     *
     * <p>This method only performs stage 1 and stage 2. The runtime activates
     * the new versions after the file/status commit and never vetoes it.</p>
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

        String operationId = UUID.randomUUID().toString();
        List<Path> downloads = new ArrayList<>();
        for (DependencyResolver.PlanStep step : plan.steps()) {
            RepositoryManager.PackageCandidate candidate = step.candidate();
            try {
                downloads.add(downloadPackage(candidate, operationId));
            } catch (Exception e) {
                return new InstallResult(false, List.of(),
                        "Failed to download " + candidate.name() + ": " + e.getMessage());
            }
        }

        try {
            List<PackageEntry> entries = installer.installBatch(downloads, true);
            List<String> upgraded = entries.stream()
                    .map(entry -> entry.name() + " " + entry.version())
                    .collect(Collectors.toList());
            return new InstallResult(true, upgraded, null);
        } catch (Exception e) {
            return InstallResult.failedCommit(
                    "Failed to commit upgrade batch: " + e.getMessage(), e);
        }
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
        
        String operationId = UUID.randomUUID().toString();
        List<Path> downloads = new ArrayList<>();
        for (DependencyResolver.PlanStep step : plan.steps()) {
            RepositoryManager.PackageCandidate candidate = step.candidate();
            try {
                downloads.add(downloadPackage(candidate, operationId));
            } catch (Exception e) {
                return new InstallResult(false, List.of(),
                        "Failed to download " + candidate.name() + ": " + e.getMessage());
            }
        }
        try {
            List<PackageEntry> entries = installer.installBatch(downloads, false);
            List<String> fixed = entries.stream()
                    .map(entry -> entry.name() + " " + entry.version())
                    .collect(Collectors.toList());
            return new InstallResult(true, fixed, null);
        } catch (Exception e) {
            return InstallResult.failedCommit(
                    "Failed to fix broken dependencies: " + e.getMessage(), e);
        }
    }

    /**
     * Removes a package (equivalent to `apt remove`).
     */
    public RemoveResult remove(String name, String version, String channel, boolean autoRemove) {
        return remove(name, version, channel, autoRemove, () -> {
        });
    }

    /**
     * Removes a package after the runtime barrier has deactivated its modules.
     */
    public RemoveResult remove(String name, String version, String channel, boolean autoRemove,
                               Runnable barrier) {
        logger.info("APT remove: " + name + " " + version + " (" + channel + ")");

        try {
            installer.remove(name, version, channel, autoRemove, barrier);
            return new RemoveResult(true, name + " " + version, null);
        } catch (PackageInstaller.InstallException e) {
            return new RemoveResult(false, null, e.getMessage());
        }
    }

    /** Stage 1 cache cleanup exposed as {@code /<root> cache clean}. */
    public void cacheClean() throws IOException {
        cache.clean();
    }

    private Path downloadPackage(RepositoryManager.PackageCandidate candidate,
                                 String operationId) throws IOException {
        logger.debug("Downloading " + candidate.name() + " from " + candidate.downloadUrl());
        return cache.fetch(candidate, operationId);
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

    public static class InstallResult {
        /** Stage that failed; {@code null} when the whole operation succeeded. */
        public enum FailedStage { PREPARE, COMMIT, RUNTIME }

        private final boolean success;
        private final List<String> packages;
        private final String error;
        private final boolean requiresConfirmation;
        private final List<String> packagesToInstall;
        private final List<String> packagesToReplace;
        private final boolean installed;
        private final ModuleState activation;
        private final FailedStage failedStage;
        private final String moduleId;
        private final Throwable cause;

        public InstallResult(boolean success, List<String> packages, String error) {
            this(success, packages, error, false, List.of(), List.of(),
                    success, success ? ModuleState.INSTALLED : null,
                    success ? null : FailedStage.PREPARE, null, null);
        }

        public InstallResult(boolean success, List<String> packages, String error,
                             boolean requiresConfirmation, List<String> packagesToInstall,
                             List<String> packagesToReplace) {
            this(success, packages, error, requiresConfirmation, packagesToInstall,
                    packagesToReplace, false, null, null, null, null);
        }

        private InstallResult(boolean success, List<String> packages, String error,
                              boolean requiresConfirmation, List<String> packagesToInstall,
                              List<String> packagesToReplace, boolean installed,
                              ModuleState activation, FailedStage failedStage,
                              String moduleId, Throwable cause) {
            this.success = success;
            this.packages = packages;
            this.error = error;
            this.requiresConfirmation = requiresConfirmation;
            this.packagesToInstall = packagesToInstall;
            this.packagesToReplace = packagesToReplace;
            this.installed = installed;
            this.activation = activation;
            this.failedStage = failedStage;
            this.moduleId = moduleId;
            this.cause = cause;
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

        static InstallResult failedCommit(String message, Throwable cause) {
            return new InstallResult(false, List.of(), message, false, List.of(), List.of(),
                    false, null, FailedStage.COMMIT, null, cause);
        }

        /** The files are installed but activation did not finish successfully. */
        public InstallResult withRuntimeFailure(ModuleState activation, String moduleId,
                                                String message, Throwable cause) {
            return new InstallResult(false, packages, message, requiresConfirmation,
                    packagesToInstall, packagesToReplace, true, activation,
                    FailedStage.RUNTIME, moduleId, cause);
        }

        /** The files and the requested activation both succeeded. */
        public InstallResult withActivation(ModuleState activation) {
            return new InstallResult(success, packages, error, requiresConfirmation,
                    packagesToInstall, packagesToReplace, installed, activation,
                    failedStage, moduleId, cause);
        }

        public boolean success() { return success; }
        public List<String> packages() { return packages; }
        public String error() { return error; }
        public boolean requiresConfirmation() { return requiresConfirmation; }
        public List<String> packagesToInstall() { return packagesToInstall; }
        public List<String> packagesToReplace() { return packagesToReplace; }
        /** @return {@code true} when stage 2 committed the files. */
        public boolean installed() { return installed; }
        /** @return persisted activation outcome, or {@code null} before stage 3. */
        public ModuleState activation() { return activation; }
        /** @return failing stage, or {@code null} on success. */
        public FailedStage failedStage() { return failedStage; }
        /** @return module that failed activation, or {@code null}. */
        public String moduleId() { return moduleId; }
        public Throwable cause() { return cause; }

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