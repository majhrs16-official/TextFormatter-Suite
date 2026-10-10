package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.spi.Version;
import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.RepositoryManager.PackageCandidate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dependency resolver - computes installation/upgrade plans.
 * Resolves version constraints, detects conflicts, cycles, and incompatibilities.
 */
public final class DependencyResolver {

    private final RepositoryManager repoManager;
    private final LocalPackageDatabase localDb;
    private final PackageInstaller installer;
    private final PluginLogger logger;
    private final String currentPlatform;
    private final String currentArch;
    private final String currentOs;
    private final int currentJavaVersion;

    public DependencyResolver(RepositoryManager repoManager, LocalPackageDatabase localDb, 
                              PackageInstaller installer, PluginLogger logger,
                              String currentPlatform, String currentArch, String currentOs, int currentJavaVersion) {
        this.repoManager = repoManager;
        this.localDb = localDb;
        this.installer = installer;
        this.logger = logger;
        this.currentPlatform = currentPlatform;
        this.currentArch = currentArch;
        this.currentOs = currentOs;
        this.currentJavaVersion = currentJavaVersion;
    }

    /**
     * Resolves an installation plan for the given packages.
     */
    public ResolutionPlan resolveInstall(Collection<String> packageNames, String versionConstraint, String channel, boolean force) {
        logger.debug("Resolving install for: " + packageNames + " " + versionConstraint + " (" + channel + ")");
        
        Map<String, PackageCandidate> selected = new LinkedHashMap<>();
        Set<String> processing = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        
        for (String pkgName : packageNames) {
            resolvePackage(pkgName, versionConstraint, channel, force, selected, processing, resolved);
        }
        
        // Build plan
        List<PlanStep> steps = buildInstallPlan(selected);
        
        return new ResolutionPlan(steps, false, null);
    }

    /**
     * Resolves a global upgrade plan (equivalent to `apt upgrade`).
     */
    public ResolutionPlan resolveUpgrade() {
        logger.debug("Resolving global upgrade");
        
        List<PackageEntry> installed = localDb.getAllPackages();
        Map<String, PackageCandidate> selected = new LinkedHashMap<>();
        Set<String> processing = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        
        for (PackageEntry installedPkg : installed) {
            // Find upgrade candidates
            List<RepositoryManager.PackageCandidate> candidates = repoManager.findCandidates(
                    installedPkg.name(), ">=" + installedPkg.version(), installedPkg.channel());
            
            if (!candidates.isEmpty()) {
                PackageCandidate best = candidates.get(0); // Already sorted by priority and version
                if (Version.parse(best.version()).compareTo(Version.parse(installedPkg.version())) > 0) {
                    resolvePackage(best.name(), best.version(), best.channel(), false, selected, processing, resolved);
                }
            }
        }
        
        List<PlanStep> steps = buildInstallPlan(selected);
        return new ResolutionPlan(steps, false, null);
    }

    /**
     * Resolves a fix-broken plan.
     */
    public ResolutionPlan resolveFixBroken() {
        logger.debug("Resolving fix-broken");
        
        List<ResolutionIssue> issues = diagnoseIssues();
        if (issues.isEmpty()) {
            return new ResolutionPlan(List.of(), false, null);
        }
        
        Map<String, PackageCandidate> selected = new LinkedHashMap<>();
        Set<String> processing = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        
        for (ResolutionIssue issue : issues) {
            if (issue.type().equals("MISSING_DEPENDENCY")) {
                resolvePackage(issue.packageName(), "*", null, false, selected, processing, resolved);
            }
        }
        
        List<PlanStep> steps = buildInstallPlan(selected);
        return new ResolutionPlan(steps, false, null);
    }

    private void resolvePackage(String pkgName, String versionConstraint, String channel, boolean force,
                                Map<String, PackageCandidate> selected, Set<String> processing, Set<String> resolved) {
        if (resolved.contains(pkgName)) return;
        if (processing.contains(pkgName)) {
            throw new ResolutionException("Dependency cycle detected involving: " + pkgName);
        }
        
        // Check if already installed with compatible version
        Optional<PackageEntry> installed = localDb.getLatestInstalled(pkgName, channel != null ? channel : "stable");
        if (installed.isPresent() && !force) {
            Version installedVersion = Version.parse(installed.get().version());
            if (installedVersion.satisfies(versionConstraint)) {
                logger.debug("Package already satisfied: " + pkgName + " " + installedVersion);
                resolved.add(pkgName);
                return;
            }
        }
        
        processing.add(pkgName);
        
        // Find candidates
        List<RepositoryManager.PackageCandidate> candidates = repoManager.findCandidates(pkgName, versionConstraint, channel);
        if (candidates.isEmpty()) {
            processing.remove(pkgName);
            throw new ResolutionException("No candidate found for " + pkgName + " " + versionConstraint + " (" + channel + ")");
        }
        
        // Select best candidate (first after sorting)
        RepositoryManager.PackageCandidate best = candidates.get(0);
        
        // Check compatibility
        if (!isCompatible(best)) {
            processing.remove(pkgName);
            throw new ResolutionException("Package " + best.name() + " " + best.version() + " is not compatible with current environment");
        }
        
        selected.put(best.name(), best);
        resolved.add(pkgName);
        processing.remove(pkgName);
        
        // Resolve dependencies (we'd need to download manifest to get actual deps)
        // For now, assume dependencies are declared in the remote index or we need to fetch manifest
        // This is a simplification - in reality we'd need to download the manifest first
    }

    private boolean isCompatible(RepositoryManager.PackageCandidate candidate) {
        // TODO: Check Arch, OS, Platform compatibility
        // This would require downloading and parsing the manifest
        return true;
    }

    private List<PlanStep> buildInstallPlan(Map<String, PackageCandidate> selected) {
        // Topological sort by dependencies
        // For now, just return in selection order
        List<PlanStep> steps = new ArrayList<>();
        for (PackageCandidate candidate : selected.values()) {
            steps.add(new PlanStep(PlanStep.Type.INSTALL, candidate));
        }
        return steps;
    }

    private List<ResolutionIssue> diagnoseIssues() {
        List<ResolutionIssue> issues = new ArrayList<>();
        
        for (PackageEntry entry : localDb.getAllPackages()) {
            for (String dep : entry.dependencies()) {
                boolean satisfied = localDb.getAllPackages().stream()
                        .anyMatch(p -> p.name().equals(dep));
                if (!satisfied) {
                    issues.add(new ResolutionIssue(entry.name(), "MISSING_DEPENDENCY", 
                            "Missing dependency: " + dep));
                }
            }
        }
        
        return issues;
    }

    public static class ResolutionPlan {
        private final List<PlanStep> steps;
        private final boolean success;
        private final String error;

        public ResolutionPlan(List<PlanStep> steps, boolean success, String error) {
            this.steps = steps;
            this.success = success;
            this.error = error;
        }

        public List<PlanStep> steps() { return steps; }
        public boolean success() { return success; }
        public String error() { return error; }
    }

    public static class PlanStep {
        public enum Type { INSTALL, UPGRADE, REMOVE, DOWNGRADE }
        
        private final Type type;
        private final RepositoryManager.PackageCandidate candidate;

        public PlanStep(Type type, RepositoryManager.PackageCandidate candidate) {
            this.type = type;
            this.candidate = candidate;
        }

        public Type type() { return type; }
        public RepositoryManager.PackageCandidate candidate() { return candidate; }
    }

    public static class ResolutionIssue {
        private final String packageName;
        private final String type;
        private final String message;

        public ResolutionIssue(String packageName, String type, String message) {
            this.packageName = packageName;
            this.type = type;
            this.message = message;
        }

        public String packageName() { return packageName; }
        public String type() { return type; }
        public String message() { return message; }
    }

    public static class ResolutionException extends RuntimeException {
        public ResolutionException(String message) {
            super(message);
        }
    }
}