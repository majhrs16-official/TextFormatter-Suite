package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.spi.Version;
import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.apt.RepositoryManager.PackageCandidate;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    
    // HTTP client for downloading manifests (same as AptInstaller)
    private final OkHttpClient httpClient;
    
    // Cache for package manifests to avoid repeated downloads
    private final Map<String, PackageManifest> manifestCache = new ConcurrentHashMap<>();

    /** Hard limits so a hostile repository cannot exhaust the resolver. */
    static final int MAX_DEPENDENCY_DEPTH = 64;
    static final int MAX_RESOLVED_PACKAGES = 1024;
    static final long MAX_MANIFEST_BYTES = 2L * 1024 * 1024;
    static final long MAX_PACKAGE_BYTES = 512L * 1024 * 1024;

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
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
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
            resolvePackage(pkgName, versionConstraint, channel, force, selected, processing, resolved, 0);
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
                    resolvePackage(best.name(), best.version(), best.channel(), false, selected, processing, resolved, 0);
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
                resolvePackage(issue.packageName(), "*", null, false, selected, processing, resolved, 0);
            }
        }
        
        List<PlanStep> steps = buildInstallPlan(selected);
        return new ResolutionPlan(steps, true, null);
    }

    private void resolvePackage(String pkgName, String versionConstraint, String channel, boolean force,
                                Map<String, ResolvedPackage> selected, Set<String> processing, Set<String> resolved,
                                int depth) {
        if (resolved.contains(pkgName)) return;
        if (depth > MAX_DEPENDENCY_DEPTH) {
            throw new ResolutionException("Dependency graph exceeds maximum depth: " + MAX_DEPENDENCY_DEPTH);
        }
        if (selected.size() >= MAX_RESOLVED_PACKAGES
                || resolved.size() >= MAX_RESOLVED_PACKAGES
                || processing.size() >= MAX_RESOLVED_PACKAGES) {
            throw new ResolutionException("Dependency graph exceeds maximum package count: " + MAX_RESOLVED_PACKAGES);
        }
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
        
        // Try candidates in order until one works (fallback)
        ResolutionException lastException = null;
        for (PackageCandidate candidate : candidates) {
            // Check compatibility
            if (!isCompatible(candidate)) {
                logger.debug("Candidate " + candidate.name() + " " + candidate.version() + " not compatible, trying next");
                continue;
            }
            
            try {
                // Download and parse manifest to get dependencies
                PackageManifest manifest = fetchAndParseManifest(candidate);
                
                // Create resolved package with dependencies
                ResolvedPackage resolvedPkg = new ResolvedPackage(candidate, manifest.dependencies());
                
                selected.put(candidate.name(), resolvedPkg);
                resolved.add(pkgName);
                processing.remove(pkgName);
                
                // Recursively resolve dependencies
                for (String depName : manifest.dependencies()) {
                    String depConstraint = getDependencyConstraint(manifest, depName);
                    resolvePackage(depName, depConstraint, channel, false, selected, processing, resolved, depth + 1);
                }
                
                // Success - return
                return;
                
            } catch (ResolutionException e) {
                lastException = e;
                logger.debug("Candidate " + candidate.name() + " " + candidate.version() + " failed: " + e.getMessage() + ", trying next");
                // Remove any partially added packages from this failed attempt
                selected.remove(candidate.name());
                resolved.remove(pkgName);
                // Continue to next candidate
            } catch (Exception e) {
                lastException = new ResolutionException("Failed to process candidate " + candidate.name() + ": " + e.getMessage(), e);
                logger.debug("Candidate " + candidate.name() + " " + candidate.version() + " failed with error: " + e.getMessage() + ", trying next");
                selected.remove(candidate.name());
                resolved.remove(pkgName);
                // Continue to next candidate
            }
        }
        
        // All candidates failed
        processing.remove(pkgName);
        if (lastException != null) {
            throw lastException;
        } else {
            throw new ResolutionException("All candidates failed for " + pkgName + " " + versionConstraint + " (" + channel + ")");
        }
    }

    private String getDependencyConstraint(PackageManifest manifest, String depName) {
        // Parse version constraint from manifest dependencies
        for (String dep : manifest.dependencies()) {
            ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse(dep);
            if (spec != null && spec.name().equals(depName)) {
                return spec.constraint().isEmpty() ? "*" : spec.constraint();
            }
        }
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
                if (manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                    throw new IOException("Manifest too large: " + manifestEntry.getSize() + " bytes");
                }
                
                try (var is = zip.getInputStream(manifestEntry)) {
                    byte[] bytes = is.readNBytes((int) MAX_MANIFEST_BYTES + 1);
                    if (bytes.length > MAX_MANIFEST_BYTES) {
                        throw new IOException("Manifest exceeds " + MAX_MANIFEST_BYTES + " bytes");
                    }
                    return parseManifest(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private Path downloadPackageToTemp(PackageCandidate candidate, Path dest) throws IOException, InterruptedException {
        if (candidate.size() <= 0 || candidate.size() > MAX_PACKAGE_BYTES) {
            throw new IOException("Invalid package size: " + candidate.size());
        }

        String url = candidate.downloadUrl();
        
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
        try {
            PackageManifest manifest = fetchAndParseManifest(candidate);
            String pkgArch = manifest.mainPackage().arch();
            String pkgPlatform = manifest.mainPackage().platform();
            String pkgOs = manifest.mainPackage().os();
            
            // Check architecture compatibility
            if (!isArchCompatible(pkgArch)) {
                logger.debug("Package " + candidate.name() + " arch " + pkgArch + " not compatible with " + currentArch);
                return false;
            }
            
            // Check platform compatibility
            if (!isPlatformCompatible(pkgPlatform)) {
                logger.debug("Package " + candidate.name() + " platform " + pkgPlatform + " not compatible with " + currentPlatform);
                return false;
            }
            
            // Check OS compatibility
            if (!isOsCompatible(pkgOs)) {
                logger.debug("Package " + candidate.name() + " os " + pkgOs + " not compatible with " + currentOs);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            logger.warn("Failed to check compatibility for " + candidate.name() + ": " + e.getMessage());
            return false;
        }
    }
    
    private boolean isArchCompatible(String pkgArch) {
        if ("none".equalsIgnoreCase(pkgArch) || "any".equalsIgnoreCase(pkgArch) || pkgArch.isBlank()) {
            return true;
        }
        // Normalize common arch names
        String normalizedPkg = normalizeArch(pkgArch);
        String normalizedCurrent = normalizeArch(currentArch);
        return normalizedPkg.equals(normalizedCurrent);
    }
    
    private boolean isPlatformCompatible(String pkgPlatform) {
        if ("common".equalsIgnoreCase(pkgPlatform) || "any".equalsIgnoreCase(pkgPlatform) || pkgPlatform.isBlank()) {
            return true;
        }
        return pkgPlatform.equalsIgnoreCase(currentPlatform);
    }
    
    private boolean isOsCompatible(String pkgOs) {
        if ("none".equalsIgnoreCase(pkgOs) || "any".equalsIgnoreCase(pkgOs) || pkgOs.isBlank()) {
            return true;
        }
        return pkgOs.equalsIgnoreCase(currentOs);
    }
    
    private String normalizeArch(String arch) {
        return switch (arch.toLowerCase()) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "i386", "i686", "x86" -> "i386";
            default -> arch.toLowerCase();
        };
    }

    private List<PlanStep> buildInstallPlan(Map<String, ResolvedPackage> selected) {
        // Topological sort by dependencies (Kahn's algorithm)
        // For installation, we need DEPENDENCIES installed BEFORE the packages that depend on them.
        // Edge direction: dependency -> dependent (so dependents have in-degree > 0)
        Map<String, ResolvedPackage> graph = new HashMap<>(selected);
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        
        // Initialize in-degrees and dependents map
        for (String name : graph.keySet()) {
            inDegree.put(name, 0);
            dependents.put(name, new HashSet<>());
        }
        
        // Build reverse edges: for each pkg -> dep, add edge dep -> pkg
        // This means dep must be installed before pkg
        for (ResolvedPackage pkg : graph.values()) {
            String pkgName = pkg.candidate().name();
            for (String dep : pkg.dependencies()) {
                if (graph.containsKey(dep)) {
                    dependents.get(dep).add(pkgName);
                    inDegree.put(pkgName, inDegree.getOrDefault(pkgName, 0) + 1);
                }
            }
        }
        
        // Kahn's algorithm: start with nodes that have in-degree 0 (no dependencies, or deps outside graph)
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
            
            // Decrease in-degree of dependents (packages that depend on this one)
            for (String dependent : dependents.getOrDefault(name, Collections.emptySet())) {
                int newDegree = inDegree.get(dependent) - 1;
                inDegree.put(dependent, newDegree);
                if (newDegree == 0) {
                    queue.add(dependent);
                }
            }
        }
        
        if (sorted.size() != graph.size()) {
            throw new ResolutionException("Dependency cycle detected in install plan");
        }
        
        // Build steps in topological order (dependencies first)
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

    static class ResolvedPackage {
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