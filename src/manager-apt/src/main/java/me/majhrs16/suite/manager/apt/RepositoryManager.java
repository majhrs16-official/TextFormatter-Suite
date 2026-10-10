package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.RepositoryConfig;
import me.majhrs16.suite.manager.spi.Version;
import me.majhrs16.suite.manager.core.LocalPackageDatabase;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Repository manager (APT equivalent) - handles remote index downloading,
 * caching, and candidate package discovery.
 */
public final class RepositoryManager {

    private final LocalPackageDatabase database;
    private final List<RepositoryConfig> repositories;
    private final Path listsDir;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final PluginLogger logger;

    private final Map<String, List<ManifestParser.RemoteIndexEntry>> cachedIndexes = new ConcurrentHashMap<>();

    public RepositoryManager(LocalPackageDatabase database, List<RepositoryConfig> repositories, PluginLogger logger) {
        this.database = database;
        this.repositories = repositories;
        this.listsDir = database.getListsDir();
        this.logger = logger;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    /**
     * Updates all enabled repository indexes (equivalent to `apt update`).
     */
    public UpdateResult updateAll() throws IOException {
        logger.info("Updating repository indexes...");
        
        UpdateResult result = new UpdateResult();
        
        for (RepositoryConfig repo : repositories) {
            if (!repo.enabled()) {
                logger.debug("Skipping disabled repository: " + repo.name());
                continue;
            }

            try {
                List<ManifestParser.RemoteIndexEntry> entries = downloadIndex(repo);
                cachedIndexes.put(repo.name(), entries);
                
                // Save to local cache
                Path indexFile = listsDir.resolve(sanitizeFileName(repo.name()) + ".list");
                saveIndex(indexFile, entries);
                
                result.updated.add(repo.name());
                logger.info("Updated repository: " + repo.name() + " (" + entries.size() + " packages)");
            } catch (Exception e) {
                result.failed.put(repo.name(), e.getMessage());
                logger.error("Failed to update repository " + repo.name() + ": " + e.getMessage());
            }
        }
        
        return result;
    }

    /**
     * Downloads and parses a repository index.
     */
    private List<ManifestParser.RemoteIndexEntry> downloadIndex(RepositoryConfig repo) throws IOException {
        String indexUrl = buildIndexUrl(repo);
        logger.debug("Downloading index from: " + indexUrl);

        String content;
        if ("file".equals(repo.url().toLowerCase().startsWith("file://") ? "file" : "")) {
            content = readLocalIndex(repo);
        } else {
            content = fetchRemoteIndex(indexUrl);
        }

        // Parse based on repository type
        List<ManifestParser.RemoteIndexEntry> entries;
        if (repo.type() == RepositoryConfig.RepositoryType.GITHUB) {
            entries = parseGitHubReleases(content);
        } else {
            try {
                entries = ManifestParser.parseRemoteIndex(content);
            } catch (ManifestParser.ParseException e) {
                throw new IOException("Failed to parse remote index: " + e.getMessage(), e);
            }
        }

        // Validate entries
        for (ManifestParser.RemoteIndexEntry entry : entries) {
            if (entry.name().isBlank() || entry.url().isBlank()) {
                throw new IOException("Invalid index entry: missing name or URL");
            }
            if (entry.size() < 0) {
                throw new IOException("Invalid index entry: negative size for " + entry.name());
            }
            if (!isValidSha256(entry.sha256())) {
                throw new IOException("Invalid SHA256 for " + entry.name() + ": " + entry.sha256());
            }
        }

        return entries;
    }

    private String buildIndexUrl(RepositoryConfig repo) {
        String baseUrl = repo.url();
        if (repo.type() == RepositoryConfig.RepositoryType.GITHUB) {
            // Convert GitHub API URL to releases list
            if (baseUrl.contains("api.github.com/repos")) {
                return baseUrl.replace("api.github.com/repos", "github.com") + "/releases";
            }
            return baseUrl + "/releases";
        }
        return baseUrl + "/packages.list";
    }

    private String fetchRemoteIndex(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            return response.body().string();
        }
    }

    private String readLocalIndex(RepositoryConfig repo) throws IOException {
        String pathStr = repo.url();
        if (pathStr.startsWith("file://")) {
            pathStr = pathStr.substring(7);
        }
        Path indexPath = Path.of(pathStr).resolve("packages.list");
        if (!Files.exists(indexPath)) {
            throw new IOException("Local index not found: " + indexPath);
        }
        return Files.readString(indexPath);
    }

    private List<ManifestParser.RemoteIndexEntry> parseGitHubReleases(String content) {
        List<ManifestParser.RemoteIndexEntry> entries = new ArrayList<>();
        JsonArray releases = JsonParser.parseString(content).getAsJsonArray();
        
        for (JsonElement release : releases) {
            JsonObject r = release.getAsJsonObject();
            String tagName = r.get("tag_name").getAsString();
            String version = tagName.replace("v", "");
            JsonArray assets = r.getAsJsonArray("assets");
            
            for (JsonElement asset : assets) {
                JsonObject a = asset.getAsJsonObject();
                String name = a.get("name").getAsString();
                if (name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")) {
                    // Extract artifact name
                    String artifact = name.substring(0, name.lastIndexOf('-'));
                    String downloadUrl = a.get("browser_download_url").getAsString();
                    long size = a.get("size").getAsLong();
                    
                    // Find SHA256 asset
                    String sha256 = null;
                    for (JsonElement shaAsset : assets) {
                        JsonObject sa = shaAsset.getAsJsonObject();
                        if (sa.get("name").getAsString().equals(name + ".sha256")) {
                            String shaUrl = sa.get("browser_download_url").getAsString();
                            try {
                                sha256 = fetchRemoteIndex(shaUrl).trim();
                            } catch (IOException ignored) {}
                            break;
                        }
                    }
                    
                    if (sha256 != null && isValidSha256(sha256)) {
                        entries.add(new ManifestParser.RemoteIndexEntry(artifact, downloadUrl, size, sha256));
                    }
                }
            }
        }
        return entries;
    }

    private void saveIndex(Path indexFile, List<ManifestParser.RemoteIndexEntry> entries) throws IOException {
        Files.createDirectories(indexFile.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Repository index: ").append(indexFile.getFileName()).append("\n");
        sb.append("# Updated: ").append(Instant.now()).append("\n");
        sb.append("# Entries: ").append(entries.size()).append("\n\n");
        
        for (ManifestParser.RemoteIndexEntry entry : entries) {
            sb.append(entry.name()).append('\t')
                    .append(entry.url()).append('\t')
                    .append(entry.size()).append('\t')
                    .append(entry.sha256()).append('\n');
        }
        Files.writeString(indexFile, sb.toString());
    }

    /**
     * Finds candidate packages matching a name and version constraint.
     */
    public List<PackageCandidate> findCandidates(String packageName, String versionConstraint, String channel) {
        List<PackageCandidate> candidates = new ArrayList<>();
        
        for (RepositoryConfig repo : repositories) {
            if (!repo.enabled()) continue;
            
            List<ManifestParser.RemoteIndexEntry> entries = cachedIndexes.get(repo.name());
            if (entries == null) continue;
            
            for (ManifestParser.RemoteIndexEntry entry : entries) {
                if (!entry.name().equals(packageName)) continue;
                
                // Extract version from URL or use a default
                String version = extractVersionFromUrl(entry.url());
                if (version == null) continue;
                
                Version v = Version.tryParse(version);
                if (v == null) continue;
                
                // Check version constraint
                if (v.satisfies(versionConstraint)) {
                    // Check channel (if specified in repo options)
                    String repoChannel = repo.options().getOrDefault("channel", "stable");
                    if (channel == null || channel.equals(repoChannel)) {
                        candidates.add(new PackageCandidate(
                                entry.name(), version, repoChannel, repo.name(),
                                entry.url(), entry.size(), entry.sha256(), repo.priority()));
                    }
                }
            }
        }
        
        // Sort by repository priority (lower = higher priority), then by version (newer first)
        candidates.sort(Comparator
                .comparingInt(PackageCandidate::repoPriority)
                .thenComparing(c -> Version.parse(c.version()), Comparator.reverseOrder()));
        
        return candidates;
    }

    /**
     * Gets all available packages across all repositories.
     */
    public Map<String, List<PackageCandidate>> getAllAvailablePackages() {
        Map<String, List<PackageCandidate>> result = new LinkedHashMap<>();
        
        for (RepositoryConfig repo : repositories) {
            if (!repo.enabled()) continue;
            
            List<ManifestParser.RemoteIndexEntry> entries = cachedIndexes.get(repo.name());
            if (entries == null) continue;
            
            String repoChannel = repo.options().getOrDefault("channel", "stable");
            
            for (ManifestParser.RemoteIndexEntry entry : entries) {
                String version = extractVersionFromUrl(entry.url());
                if (version == null) continue;
                
                PackageCandidate candidate = new PackageCandidate(
                        entry.name(), version, repoChannel, repo.name(),
                        entry.url(), entry.size(), entry.sha256(), repo.priority());
                
                result.computeIfAbsent(entry.name(), k -> new ArrayList<>()).add(candidate);
            }
        }
        
        // Sort each package's candidates
        for (List<PackageCandidate> candidates : result.values()) {
            candidates.sort(Comparator
                    .comparingInt(PackageCandidate::repoPriority)
                    .thenComparing(c -> Version.parse(c.version()), Comparator.reverseOrder()));
        }
        
        return result;
    }

    private String extractVersionFromUrl(String url) {
        // Expected format: .../artifact-1.2.3.jar or .../artifact-1.2.3-beta.jar
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String fileName = url.substring(lastSlash + 1);
        if (!fileName.endsWith(".jar")) return null;
        
        // Remove .jar
        String withoutExt = fileName.substring(0, fileName.length() - 4);
        // Find last dash before version
        int lastDash = withoutExt.lastIndexOf('-');
        if (lastDash < 0) return null;
        
        return withoutExt.substring(lastDash + 1);
    }

    private boolean isValidSha256(String sha256) {
        return sha256 != null && sha256.matches("^[a-fA-F0-9]{64}$");
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public void loadCachedIndexes() throws IOException {
        if (!Files.exists(listsDir)) return;
        
        try (var stream = Files.list(listsDir)) {
            for (Path indexFile : stream.filter(p -> p.toString().endsWith(".list")).toList()) {
                String content = Files.readString(indexFile);
                List<ManifestParser.RemoteIndexEntry> entries;
                try {
                    entries = ManifestParser.parseRemoteIndex(content);
                } catch (ManifestParser.ParseException e) {
                    logger.warn("Failed to parse cached index " + indexFile + ": " + e.getMessage());
                    continue;
                }
                String repoName = indexFile.getFileName().toString().replace(".list", "");
                cachedIndexes.put(repoName, entries);
            }
        }
    }

    public static class UpdateResult {
        public final List<String> updated = new ArrayList<>();
        public final Map<String, String> failed = new HashMap<>();
        
        public boolean hasFailures() { return !failed.isEmpty(); }
        public int updatedCount() { return updated.size(); }
        public int failedCount() { return failed.size(); }
    }

    public static class PackageCandidate {
        private final String name;
        private final String version;
        private final String channel;
        private final String repository;
        private final String downloadUrl;
        private final long size;
        private final String sha256;
        private final int repoPriority;

        public PackageCandidate(String name, String version, String channel, String repository,
                                String downloadUrl, long size, String sha256, int repoPriority) {
            this.name = name;
            this.version = version;
            this.channel = channel;
            this.repository = repository;
            this.downloadUrl = downloadUrl;
            this.size = size;
            this.sha256 = sha256;
            this.repoPriority = repoPriority;
        }

        public String name() { return name; }
        public String version() { return version; }
        public String channel() { return channel; }
        public String repository() { return repository; }
        public String downloadUrl() { return downloadUrl; }
        public long size() { return size; }
        public String sha256() { return sha256; }
        public int repoPriority() { return repoPriority; }

        @Override
        public String toString() {
            return name + " " + version + " (" + channel + ") from " + repository;
        }
    }
}