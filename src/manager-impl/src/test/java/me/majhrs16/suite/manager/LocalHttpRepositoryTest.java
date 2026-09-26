package me.majhrs16.suite.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.manager.Environment;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.kernel.ModuleLoader;
import me.majhrs16.suite.manager.DefaultModuleLifecycle;
import me.majhrs16.suite.manager.ModuleCoordinate;
import me.majhrs16.suite.manager.ModuleLifecycle;
import me.majhrs16.suite.host.config.HostConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Module Manager with local HTTP repositories.
 * Verifies the repository abstraction works with file:// and http:// sources.
 */
class LocalHttpRepositoryTest {

    private static final String TEST_GROUP = "me.majhrs16";
    private static final String TEST_ARTIFACT = "suite-test-module";
    private static final String TEST_VERSION = "1.0.0";

    @TempDir
    Path tempDir;

    @Test
    void testLocalRepositoryResolution() throws Exception {
        // Create a test module JAR with module.yml manifest
        Path moduleJar = createTestModuleJar(tempDir);
        
        // Create local repository structure
        Path repoDir = tempDir.resolve("local-repo");
        Files.createDirectories(repoDir);
        
        // Copy module JAR to repo
        Path repoJar = repoDir.resolve(TEST_ARTIFACT + "-" + TEST_VERSION + ".jar");
        Files.copy(moduleJar, repoJar);
        
        // Create releases.json
        String releasesJson = """
            [
              {
                "tag_name": "v%s",
                "name": "Test Module %s",
                "published_at": "2024-01-01T00:00:00Z",
                "assets": [
                  {
                    "name": "%s-%s.jar",
                    "browser_download_url": "file://%s/%s-%s.jar",
                    "size": %d
                  },
                  {
                    "name": "%s-%s.jar.sha256",
                    "browser_download_url": "file://%s/%s-%s.jar.sha256",
                    "size": 64
                  }
                ]
              }
            ]
            """.formatted(TEST_VERSION, TEST_VERSION, TEST_ARTIFACT, TEST_VERSION, repoDir.toAbsolutePath(), TEST_ARTIFACT, TEST_VERSION, repoJar.toFile().length(),
                          TEST_ARTIFACT, TEST_VERSION, repoDir.toAbsolutePath(), TEST_ARTIFACT, TEST_VERSION);
        
        Files.writeString(repoDir.resolve("releases.json"), releasesJson);
        
        // Create SHA256 file
        String sha256 = computeSha256(repoJar);
        Files.writeString(repoDir.resolve(TEST_ARTIFACT + "-" + TEST_VERSION + ".jar.sha256"), sha256);
        
        // Configure repository
        HostConfig.Repository repo = new HostConfig.Repository(
            "local-test",
            repoDir.toUri().toString(),
            "local",
            true
        );
        
        // Test resolution
        PluginLogger logger = new PluginLogger() {
            @Override public void info(String m, Object... a) { System.out.println("[INFO] " + String.format(m, a)); }
            @Override public void warn(String m, Object... a) { System.out.println("[WARN] " + String.format(m, a)); }
            @Override public void error(String m, Object... a) { System.err.println("[ERROR] " + String.format(m, a)); }
            @Override public void error(String m, Throwable t) { System.err.println("[ERROR] " + m); t.printStackTrace(); }
            @Override public void debug(String m, Object... a) { }
        };
        
        DefaultModuleLifecycle lifecycle = new DefaultModuleLifecycle(
            tempDir.resolve("cache"),
            logger,
            List.of(repo)
        );
        
        ModuleCoordinate coordinate = ModuleCoordinate.of(TEST_GROUP, TEST_ARTIFACT, TEST_VERSION);
        Environment env = createTestEnvironment();
        
        me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult result = lifecycle.resolve(coordinate, env, false);
        
        assertTrue(result instanceof me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult.Success, 
            "Expected success but got: " + result);
        
        if (result instanceof me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult.Success success) {
            assertEquals(TEST_ARTIFACT, success.module().descriptor().coordinate().artifact());
            assertEquals(TEST_VERSION, success.module().descriptor().version().toString());
            assertNotNull(success.module().downloadUrl());
            assertEquals(sha256, success.module().sha256());
        }
    }

@Test
    void testHttpRepositoryResolution() throws Exception {
        // Create a test module JAR
        Path moduleJar = createTestModuleJar(tempDir);
        
        // Start local HTTP server
        HttpTestServer server = new HttpTestServer();
        try {
            server.start();
            
            // Upload module to server
            String sha256 = computeSha256(moduleJar);
            server.addModule(TEST_ARTIFACT, TEST_VERSION, moduleJar, sha256);
            
            // Configure HTTP repository
            HostConfig.Repository repo = new HostConfig.Repository(
                "http-test",
                server.getBaseUrl(),
                "http",
                true
            );
            
            PluginLogger logger = new PluginLogger() {
                @Override public void info(String m, Object... a) { }
                @Override public void warn(String m, Object... a) { }
                @Override public void error(String m, Object... a) { }
                @Override public void error(String m, Throwable t) { }
                @Override public void debug(String m, Object... a) { }
            };
            
            DefaultModuleLifecycle lifecycle = new DefaultModuleLifecycle(
                tempDir.resolve("cache"),
                logger,
                List.of(repo)
            );
            
            ModuleCoordinate coordinate = ModuleCoordinate.of(TEST_GROUP, TEST_ARTIFACT, TEST_VERSION);
            Environment env = createTestEnvironment();
            
            me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult result = lifecycle.resolve(coordinate, env, false);
            
            assertTrue(result instanceof me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult.Success,
                "Expected success but got: " + result);
            
            if (result instanceof me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult.Success success) {
                assertEquals(TEST_ARTIFACT, success.module().descriptor().coordinate().artifact());
                assertEquals(TEST_VERSION, success.module().descriptor().version().toString());
            }
            
        } finally {
            server.stop();
        }
    }

    @Test
    void testRepositoryFallbackOrder() throws Exception {
        // Create two repositories - first one fails, second succeeds
        Path moduleJar = createTestModuleJar(tempDir);
        
        // Repo 1: Empty (no matching module)
        Path emptyRepo = tempDir.resolve("empty-repo");
        Files.createDirectories(emptyRepo);
        Files.writeString(emptyRepo.resolve("releases.json"), "[]");
        
        // Repo 2: Has the module
        Path goodRepo = tempDir.resolve("good-repo");
        Files.createDirectories(goodRepo);
        Path repoJar = goodRepo.resolve(TEST_ARTIFACT + "-" + TEST_VERSION + ".jar");
        Files.copy(moduleJar, repoJar);
        
        String releasesJson = """
            [{
              "tag_name": "v%s",
              "name": "Test Module %s",
              "published_at": "2024-01-01T00:00:00Z",
              "assets": [
                {"name": "%s-%s.jar", "browser_download_url": "file://%s/%s-%s.jar", "size": %d},
                {"name": "%s-%s.jar.sha256", "browser_download_url": "file://%s/%s-%s.jar.sha256", "size": 64}
              ]
            }]
            """.formatted(TEST_VERSION, TEST_VERSION, TEST_ARTIFACT, TEST_VERSION, goodRepo.toAbsolutePath(), TEST_ARTIFACT, TEST_VERSION, repoJar.toFile().length(),
                          TEST_ARTIFACT, TEST_VERSION, goodRepo.toAbsolutePath(), TEST_ARTIFACT, TEST_VERSION);
        
        Files.writeString(goodRepo.resolve("releases.json"), releasesJson);
        Files.writeString(goodRepo.resolve(TEST_ARTIFACT + "-" + TEST_VERSION + ".jar.sha256"), computeSha256(repoJar));
        
        // Configure repositories - empty first, good second
        HostConfig.Repository repo1 = new HostConfig.Repository("empty", emptyRepo.toUri().toString(), "local", true);
        HostConfig.Repository repo2 = new HostConfig.Repository("good", goodRepo.toUri().toString(), "local", true);
        
        PluginLogger logger = new PluginLogger() {
            @Override public void info(String m, Object... a) { System.out.println("[INFO] " + String.format(m, a)); }
            @Override public void warn(String m, Object... a) { System.out.println("[WARN] " + String.format(m, a)); }
            @Override public void error(String m, Object... a) { System.err.println("[ERROR] " + String.format(m, a)); }
            @Override public void error(String m, Throwable t) { System.err.println("[ERROR] " + m); t.printStackTrace(); }
            @Override public void debug(String m, Object... a) { }
        };
        
        DefaultModuleLifecycle lifecycle = new DefaultModuleLifecycle(
            tempDir.resolve("cache"),
            logger,
            List.of(repo1, repo2)
        );
        
        ModuleCoordinate coordinate = ModuleCoordinate.of(TEST_GROUP, TEST_ARTIFACT, TEST_VERSION);
        Environment env = createTestEnvironment();
        
        me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult result = lifecycle.resolve(coordinate, env, false);
        
        // Should succeed with second repository
        assertTrue(result instanceof me.majhrs16.suite.manager.ModuleLifecycle.ResolutionResult.Success,
            "Expected success with fallback but got: " + result);
    }

    @Test
    void testDiscoverAvailableModules() throws Exception {
        Path moduleJar = createTestModuleJar(tempDir);
        
        Path repoDir = tempDir.resolve("discover-repo");
        Files.createDirectories(repoDir);
        
        Path repoJar = repoDir.resolve(TEST_ARTIFACT + "-" + TEST_VERSION + ".jar");
        Files.copy(moduleJar, repoJar);
        
        String releasesJson = """
            [{
              "tag_name": "v%s",
              "name": "Test Module %s",
              "published_at": "2024-01-01T00:00:00Z",
              "assets": [
                {"name": "%s-%s.jar", "browser_download_url": "file://%s/%s-%s.jar", "size": %d},
                {"name": "%s-%s.jar.sha256", "browser_download_url": "file://%s/%s-%s.jar.sha256", "size": 64}
              ]
            }]
            """.formatted(TEST_VERSION, TEST_VERSION, TEST_ARTIFACT, TEST_VERSION, repoDir.toAbsolutePath(), TEST_ARTIFACT, TEST_VERSION, repoJar.toFile().length(),
                          TEST_ARTIFACT, TEST_VERSION, repoDir.toAbsolutePath(), TEST_ARTIFACT, TEST_VERSION);
        
        Files.writeString(repoDir.resolve("releases.json"), releasesJson);
        Files.writeString(repoDir.resolve(TEST_ARTIFACT + "-" + TEST_VERSION + ".jar.sha256"), computeSha256(repoJar));
        
        HostConfig.Repository repo = new HostConfig.Repository("discover", repoDir.toUri().toString(), "local", true);
        
        PluginLogger logger = new PluginLogger() {
            @Override public void info(String m, Object... a) { System.out.println("[INFO] " + String.format(m, a)); }
            @Override public void warn(String m, Object... a) { System.out.println("[WARN] " + String.format(m, a)); }
            @Override public void error(String m, Object... a) { System.err.println("[ERROR] " + String.format(m, a)); }
            @Override public void error(String m, Throwable t) { System.err.println("[ERROR] " + m); t.printStackTrace(); }
            @Override public void debug(String m, Object... a) { }
        };
        
        DefaultModuleLifecycle lifecycle = new DefaultModuleLifecycle(
            tempDir.resolve("cache"),
            logger,
            List.of(repo)
        );
        
        List<ModuleCoordinate> available = lifecycle.discoverAvailableModules();
        
        assertFalse(available.isEmpty(), "Should discover at least one module");
        assertTrue(available.stream().anyMatch(c -> c.artifact().equals(TEST_ARTIFACT)),
            "Should discover test module");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Path createTestModuleJar(Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve(TEST_ARTIFACT + "-" + TEST_VERSION + ".jar");
        
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Module-Name"), TEST_ARTIFACT);
        manifest.getMainAttributes().put(new Attributes.Name("Module-Version"), TEST_VERSION);
        
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()), manifest)) {
            // Add module.yml - no external dependencies for isolated test
            String moduleYml = """
                name: %s
                version: %s
                description: Test module for HTTP repository testing
                artifact: %s
                """.formatted(TEST_ARTIFACT, TEST_VERSION, TEST_ARTIFACT);
            
            JarEntry entry = new JarEntry("module.yml");
            jos.putNextEntry(entry);
            jos.write(moduleYml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            
            // Add a dummy class
            String dummyClass = """
                package me.majhrs16.suite.testmodule;
                public class TestModule {}
                """;
            entry = new JarEntry("me/majhrs16/suite/testmodule/TestModule.class");
            jos.putNextEntry(entry);
            jos.write(dummyClass.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        
        return jarPath;
    }

    private Environment createTestEnvironment() {
        Map<String, String> sysProps = new HashMap<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            sysProps.put(key, System.getProperty(key));
        }
        return new Environment(
            "common",
            Runtime.version().feature(),
            "1.20.6",
            SemVer.of(2, 1, 0),
            Map.of(),
            Set.of("channel-api", "iflow-api", "textformatter-api", "translation-api", "common"),
            sysProps
        );
    }

    private String computeSha256(Path file) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
        try (InputStream is = Files.newInputStream(file)) {
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
    }

    // Simple HTTP test server
    static class HttpTestServer {
        private final HttpServer server;
        private final Map<String, ModuleData> modules = new HashMap<>();
        private final int port;
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        HttpTestServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.port = server.getAddress().getPort();
            
            server.createContext("/releases", exchange -> {
                String json = GSON.toJson(buildReleasesArray());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(json.getBytes());
                }
            });
            
            server.createContext("/download", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");
                // Path format: /download/artifact/version -> parts = ["", "download", "artifact", "version"]
                if (parts.length >= 4) {
                    String artifact = parts[2];
                    String version = parts[3];
                    ModuleData data = modules.get(artifact + ":" + version);
                    if (data != null) {
                        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
                        exchange.sendResponseHeaders(200, data.jarBytes.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(data.jarBytes);
                        }
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            
            server.createContext("/sha256", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");
                // Path format: /sha256/artifact/version -> parts = ["", "sha256", "artifact", "version"]
                if (parts.length >= 4) {
                    String artifact = parts[2];
                    String version = parts[3];
                    ModuleData data = modules.get(artifact + ":" + version);
                    if (data != null) {
                        exchange.getResponseHeaders().set("Content-Type", "text/plain");
                        exchange.sendResponseHeaders(200, data.sha256.getBytes().length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(data.sha256.getBytes());
                        }
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            
            server.setExecutor(null); // Use default executor
        }

        void start() {
            server.start();
        }

        void stop() {
            server.stop(0);
        }

        String getBaseUrl() {
            return "http://localhost:" + port;
        }

        void addModule(String artifact, String version, Path jarPath, String sha256) throws IOException {
            byte[] jarBytes = Files.readAllBytes(jarPath);
            modules.put(artifact + ":" + version, new ModuleData(artifact, version, jarBytes, sha256));
        }

        private List<JsonObject> buildReleasesArray() {
            List<JsonObject> releases = new ArrayList<>();
            for (ModuleData data : modules.values()) {
                JsonObject release = new JsonObject();
                release.addProperty("tag_name", "v" + data.version);
                release.addProperty("name", data.artifact + " " + data.version);
                release.addProperty("published_at", "2024-01-01T00:00:00Z");
                
                JsonArray assets = new JsonArray();
                JsonObject mainAsset = new JsonObject();
                mainAsset.addProperty("name", data.artifact + "-" + data.version + ".jar");
                mainAsset.addProperty("browser_download_url", getBaseUrl() + "/download/" + data.artifact + "/" + data.version);
                mainAsset.addProperty("size", data.jarBytes.length);
                assets.add(mainAsset);
                
                JsonObject shaAsset = new JsonObject();
                shaAsset.addProperty("name", data.artifact + "-" + data.version + ".jar.sha256");
                shaAsset.addProperty("browser_download_url", getBaseUrl() + "/sha256/" + data.artifact + "/" + data.version);
                shaAsset.addProperty("size", data.sha256.length());
                assets.add(shaAsset);
                
                release.add("assets", assets);
                releases.add(release);
            }
            return releases;
        }

        private record ModuleData(String artifact, String version, byte[] jarBytes, String sha256) {}
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
}