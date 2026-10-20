package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.RepositoryConfig;
import me.majhrs16.suite.manager.spi.RepoConfigParser;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryManagerTest {

    @TempDir
    Path tempDir;

    private PluginLogger createLogger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };
    }

    @Test
    void parseRepoConfig_valid() throws Exception {
        String content = """
            repositories:
              - name: "test-repo"
                url: "https://example.com/packages"
                type: "github"
                enabled: true
                priority: 10
            """;
        
        Path repoYml = tempDir.resolve("repo.yml");
        java.nio.file.Files.writeString(repoYml, content);
        
        List<RepositoryConfig> configs = RepoConfigParser.parse(repoYml);
        
        assertEquals(1, configs.size());
        assertEquals("test-repo", configs.get(0).name());
        assertEquals("https://example.com/packages", configs.get(0).url());
        assertEquals(RepositoryConfig.RepositoryType.GITHUB, configs.get(0).type());
        assertTrue(configs.get(0).enabled());
        assertEquals(10, configs.get(0).priority());
    }

    @Test
    void parseRepoConfig_multipleRepos_sortedByPriority() throws Exception {
        String content = """
            repositories:
              - name: "low-priority"
                url: "https://low.example.com"
                type: "http"
                enabled: true
                priority: 100
              - name: "high-priority"
                url: "https://high.example.com"
                type: "github"
                enabled: true
                priority: 10
            """;
        
        Path repoYml = tempDir.resolve("repo.yml");
        java.nio.file.Files.writeString(repoYml, content);
        
        List<RepositoryConfig> configs = RepoConfigParser.parse(repoYml);
        
        assertEquals(2, configs.size());
        assertEquals("high-priority", configs.get(0).name());
        assertEquals("low-priority", configs.get(1).name());
    }

    @Test
    void parseRepoConfig_missingUrl_throws() throws Exception {
        String content = """
            repositories:
              - name: "test-repo"
                type: "github"
                enabled: true
            """;
        
        Path repoYml = tempDir.resolve("repo.yml");
        java.nio.file.Files.writeString(repoYml, content);
        
        assertThrows(RepoConfigParser.ParseException.class, () -> RepoConfigParser.parse(repoYml));
    }

    @Test
    void parseRemoteIndex_valid() throws Exception {
        String content = "iflow\thttps://example.com/iflow-1.0.0.zip\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        
        List<ManifestParser.RemoteIndexEntry> entries = ManifestParser.parseRemoteIndex(content);
        
        assertEquals(1, entries.size());
        assertEquals("iflow", entries.get(0).name());
        assertEquals("https://example.com/iflow-1.0.0.zip", entries.get(0).url());
        assertEquals(48213, entries.get(0).size());
    }

    @Test
    void rejectsOversizedLocalIndex() throws Exception {
        LocalPackageDatabase database = new LocalPackageDatabase(tempDir.resolve("manager"));
        Path repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("packages.list"),
            "x".repeat((int) RepositoryManager.MAX_INDEX_BYTES + 1));

        RepositoryConfig repo = new RepositoryConfig(
            "big", repoDir.toUri().toString(), RepositoryConfig.RepositoryType.LOCAL,
            true, 0, Map.of());
        RepositoryManager manager = new RepositoryManager(database, List.of(repo), createLogger());

        RepositoryManager.UpdateResult result = manager.updateAll();

        assertTrue(result.failed.containsKey("big"), result.failed.toString());
    }
}