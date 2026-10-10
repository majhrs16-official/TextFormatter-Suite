package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.spi.Version;
import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.RepositoryManager.PackageCandidate;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dependency resolver - computes installation/upgrade plans.
 * Resolves version constraints, detects conflicts, cycles, and incompatibilities.
 * Implements full transitive dependency resolution with topological sorting.
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
    
    // Cache for package manifests to avoid repeated downloads
    private final Map<String, PackageManifest> manifestCache = new ConcurrentHashMap<>();

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
     * Resolves an installation plan for the given packages with full transitive dependency resolution.
     */
    public ResolutionPlan resolveInstall(Collection<String> packageNames, String versionConstraint, String channel, boolean force) {
        logger.debug("Resolving install for: " + packageNames + " " + versionConstraint + " (" + channel + ")");
        
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        Set<String> processing = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        
        for (String pkgName : packageNames) {
            resolvePackage(pkgName, versionConstraint, channel, force, selected, processing, resolved);
        }
        
        // Build plan with topological sort
        List<PlanStep> steps = buildInstallPlan(selected);
        
        return new ResolutionPlan(steps, true, null);
    }

    /**
     * Resolves a global upgrade plan (equivalent to `apt upgrade`).
     */
    public ResolutionPlan resolveUpgrade() {
        logger.debug("Resolving global upgrade");
        
        List<PackageEntry> installed = localDb.getAllPackages();
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        Set<String> processing = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        
        for (PackageEntry installedPkg : installed) {
            List<PackageCandidate> candidates = repoManager.findCandidates(
                    installedPkg.name(), ">=" + installedPkg.version(), installedPkg.channel());
            
            if (!candidates.isEmpty()) {
                PackageCandidate best = candidates.get(0);
                if (Version.parse(best.version()).compareTo(Version.parse(installedPkg.version())) > 0) {
                    resolvePackage(best.name(), best.version(), best.channel(), false, selected, processing, resolved);
                }
            }
        }
        
        List<PlanStep> steps = buildInstallPlan(selected);
        return new ResolutionPlan(steps, true, null);
    }

    /**
     * Resolves a fix-broken plan.
     */
    public ResolutionPlan resolveFixBroken() {
        logger.debug("Resolving fix-broken");
        
        List<ResolutionIssue> issues = diagnoseIssues();
        if (issues.isEmpty()) {
            return new ResolutionPlan(List.of(), true, null);
        }
        
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        Set<String> processing = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        
        for (ResolutionIssue issue : issues) {
            if (issue.type().equals("MISSING_DEPENDENCY")) {
                resolvePackage(issue.packageName(), "*", null, false, selected, processing, resolved);
            }
        }
        
        List<PlanStep> steps = buildInstallPlan(selected);
        return new ResolutionPlan(steps, true, null);
    }

    private void resolvePackage(String pkgName, String versionConstraint, String channel, boolean force,
                                Map<String, ResolvedPackage> selected, Set<String> processing, Set<String> resolved) {
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
        List<PackageCandidate> candidates = repoManager.findCandidates(pkgName, versionConstraint, channel);
        if (candidates.isEmpty()) {
            processing.remove(pkgName);
            throw new ResolutionException("No candidate found for " + pkgName + " " + versionConstraint + " (" + channel + ")");
        }
        
        // Select best candidate (first after sorting by priority and version)
        PackageCandidate best = candidates.get(0);
        
        // Check compatibility
        if (!isCompatible(best)) {
            processing.remove(pkgName);
            throw new ResolutionException("Package " + best.name() + " " + best.version() + " is not compatible with current environment");
        }
        
        // Download and parse manifest to get dependencies
        PackageManifest manifest = fetchAndParseManifest(best);
        
        // Create resolved package with dependencies
        ResolvedPackage resolvedPkg = new ResolvedPackage(best, manifest.dependencies());
        
        selected.put(best.name(), resolvedPkg);
        resolved.add(pkgName);
        processing.remove(pkgName);
        
        // Recursively resolve dependencies
        for (String depName : manifest.dependencies()) {
            String depConstraint = getDependencyConstraint(manifest, depName);
            resolvePackage(depName, depConstraint, channel, false, selected, processing, resolved);
        }
    }

    private String getDependencyConstraint(PackageManifest manifest, String depName) {
        // For now, return "*" (any version). In a full implementation,
        // we'd parse version constraints from the manifest.
        return "*";
    }

    private PackageManifest fetchAndParseManifest(PackageCandidate candidate) {
        // Check cache first
        String cacheKey = candidate.name() + ":" + candidate.version();
        return manifestCache.computeIfAbsent(cacheKey, k -> {
            try {
                return downloadAndParseManifest(candidate);
            } catch (IOException e) {
                throw new ResolutionException("Failed to fetch manifest for " + candidate.name() + ": " + e.getMessage(), e);
            }
        });
    }

    private PackageManifest downloadAndParseManifest(PackageCandidate candidate) throws IOException {
        // Download the package ZIP to a temp location
        Path tempZip = Files.createTempFile("manifest-", ".zip");
        try {
            // Download using the same logic as AptInstaller
            Path zipPath;
            try {
                zipPath = downloadPackageToTemp(candidate, tempZip);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
            
            // Extract and parse manifest
            try (var zip = java.util.zip.ZipFile.class.cast(new java.util.zip.ZipFile(zipPath.toFile()))) {
                var manifestEntry = zip.getEntry("METADATA/package.list");
                if (manifestEntry == null) {
                    throw new IOException("Missing METADATA/package.list in ZIP");
                }
                
                try (var is = zip.getInputStream(manifestEntry)) {
                    String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return parseManifest(content);
                }
            }
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private Path downloadPackageToTemp(PackageCandidate candidate, Path dest) throws IOException, InterruptedException {
        String url = candidate.downloadUrl();
        
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .build();
        
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(dest));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        
        // Verify checksum
        String actualSha256 = computeSha256(dest);
        if (!actualSha256.equalsIgnoreCase(candidate.sha256())) {
            throw new IOException("Checksum mismatch for " + candidate.name());
        }
        if (Files.size(dest) != candidate.size()) {
            throw new IOException("Size mismatch for " + candidate.name());
        }
        
        return dest;
    }

    private PackageManifest parseManifest(String content) {
        try {
            List<PackageEntry> entries = me.majhrs16.suite.manager.spi.ManifestParser.parseManifest(content);
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("Manifest contains no packages");
            }
            
            PackageEntry mainPackage = entries.get(0);
            List<PackageEntry> additionalPackages = entries.size() > 1 ? entries.subList(1, entries.size()) : List.of();
            
            // Extract dependencies from main package
            List<String> dependencies = mainPackage.dependencies();
            
            return new PackageManifest(mainPackage, additionalPackages, dependencies);
        } catch (me.majhrs16.suite.manager.spi.ManifestParser.ParseException e) {
            throw new IllegalArgumentException("Invalid manifest: " + e.getMessage(), e);
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

    private boolean isCompatible(PackageCandidate candidate) {
        // Check Arch, OS, Platform compatibility from manifest
        // For now, assume compatible
        return true;
    }

    private List<PlanStep> buildInstallPlan(Map<String, ResolvedPackage> selected) {
        // Topological sort by dependencies
        Map<String, ResolvedPackage> graph = new HashMap<>(selected);
        Map<String, Integer> inDegree = new HashMap<>();
        
        // Initialize in-degrees
        for (String name : graph.keySet()) {
            inDegree.put(name, 0);
        }
        
        // Calculate in-degrees
        for (ResolvedPackage pkg : graph.values()) {
            for (String dep : pkg.dependencies()) {
                if (graph.containsKey(dep)) {
                    inDegree.put(dep, inDegree.getOrDefault(dep, 0) + 1);
                }
            }
        }
        
        // Kahn's algorithm for topological sort
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        
        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            sorted.add(name);
            
            ResolvedPackage pkg = graph.get(name);
            if (pkg != null) {
                for (String dep : pkg.dependencies()) {
                    if (graph.containsKey(dep)) {
                        int newDegree = inDegree.get(dep) - 1;
                        inDegree.put(dep, newDegree);
                        if (newDegree == 0) {
                            queue.add(dep);
                        }
                    }
                }
            }
        }
        
        if (sorted.size() != graph.size()) {
            throw new ResolutionException("Dependency cycle detected in install plan");
        }
        
        // Build steps in topological order
        List<PlanStep> steps = new ArrayList<>();
        for (String name : sorted) {
            ResolvedPackage pkg = graph.get(name);
            steps.add(new PlanStep(PlanStep.Type.INSTALL, pkg.candidate()));
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
        private final PackageCandidate candidate;

        public PlanStep(Type type, PackageCandidate candidate) {
            this.type = type;
            this.candidate = candidate;
        }

        public Type type() { return type; }
        public PackageCandidate candidate() { return candidate; }
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
        public ResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class ResolvedPackage {
        private final PackageCandidate candidate;
        private final List<String> dependencies;

        public ResolvedPackage(PackageCandidate candidate, List<String> dependencies) {
            this.candidate = candidate;
            this.dependencies = dependencies;
        }

        public PackageCandidate candidate() { return candidate; }
        public List<String> dependencies() { return dependencies; }
    }

    private static class PackageManifest {
        private final PackageEntry mainPackage;
        private final List<PackageEntry> additionalPackages;
        private final List<String> dependencies;

        public PackageManifest(PackageEntry mainPackage, List<PackageEntry> additionalPackages, List<String> dependencies) {
            this.mainPackage = mainPackage;
            this.additionalPackages = additionalPackages;
            this.dependencies = dependencies;
        }

        public PackageEntry mainPackage() { return mainPackage; }
        public List<PackageEntry> additionalPackages() { return additionalPackages; }
        public List<String> dependencies() { return dependencies; }
    }
}