package me.majhrs16.suite.manager.apt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.majhrs16.suite.api.spi.PluginLogger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-1 contract: content-addressed cache, resumable partial files,
 * checksum verification and a cleanup that never touches installed state.
 */
class PackageCacheTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    private final PluginLogger logger = new PluginLogger() {
        @Override public void info(String m, Object... a) { }
        @Override public void warn(String m, Object... a) { }
        @Override public void error(String m, Object... a) { }
        @Override public void error(String m, Throwable t) { }
        @Override public void debug(String m, Object... a) { }
    };

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsAndRecordsACompleteManifest() throws Exception {
        byte[] content = "hello package".getBytes(StandardCharsets.UTF_8);
        String url = serve(content, new AtomicBoolean(false));
        Path managerDir = tempDir.resolve("manager");
        PackageCache cache = cache(managerDir);

        Path downloaded = cache.fetch(candidate(content, url), "op-complete");

        assertTrue(Files.isRegularFile(downloaded));
        assertEquals("hello package", Files.readString(downloaded));
        Path manifest = managerDir.resolve("library/cache/operations/op-complete.json");
        assertTrue(Files.isRegularFile(manifest));
        String json = Files.readString(manifest);
        assertTrue(json.contains("\"status\": \"COMPLETE\""), json);
        assertTrue(json.contains(sha256(content)), json);
    }

    @Test
    void resumesAnIncompletePartialFileWithRange() throws Exception {
        byte[] content = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean sawRange = new AtomicBoolean(false);
        String url = serve(content, sawRange);
        Path managerDir = tempDir.resolve("manager");
        PackageCache cache = cache(managerDir);

        Path directory = managerDir.resolve("library/cache/downloads").resolve(sha256(content));
        Files.createDirectories(directory);
        Path part = directory.resolve("demo-1.0.0.zip.part");
        Files.write(part, java.util.Arrays.copyOf(content, 6));

        Path downloaded = cache.fetch(candidate(content, url), "op-resume");

        assertTrue(sawRange.get(), "an incomplete .part must be resumed with a Range request");
        assertEquals("0123456789abcdef", Files.readString(downloaded));
        assertFalse(Files.exists(part));
    }

    @Test
    void rejectsAChecksumMismatchAndRemovesThePartialFile() throws Exception {
        byte[] content = "corrupt me".getBytes(StandardCharsets.UTF_8);
        String url = serve(content, new AtomicBoolean(false));
        Path managerDir = tempDir.resolve("manager");
        PackageCache cache = cache(managerDir);

        RepositoryManager.PackageCandidate bad = new RepositoryManager.PackageCandidate(
                "demo", "1.0.0", "stable", "test", url, content.length,
                "0".repeat(64), 0);

        assertThrows(IOException.class, () -> cache.fetch(bad, "op-bad"));

        Path directory = managerDir.resolve("library/cache/downloads").resolve("0".repeat(64));
        assertFalse(Files.exists(directory.resolve("demo-1.0.0.zip.part")));
        assertFalse(Files.exists(directory.resolve("demo-1.0.0.zip")));
    }

    @Test
    void rejectsTraversalInTheOperationId() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String url = serve(content, new AtomicBoolean(false));
        Path managerDir = tempDir.resolve("manager");
        PackageCache cache = cache(managerDir);

        assertThrows(IOException.class,
                () -> cache.fetch(candidate(content, url), "../../escape"));
        assertFalse(Files.exists(managerDir.resolve("library/escape.json")));
    }

    @Test
    void cacheCleanOnlyRemovesTheDownloadCache() throws Exception {
        byte[] content = "installed".getBytes(StandardCharsets.UTF_8);
        String url = serve(content, new AtomicBoolean(false));
        Path managerDir = tempDir.resolve("manager");
        PackageCache cache = cache(managerDir);
        cache.fetch(candidate(content, url), "op-clean");

        Path installed = managerDir.resolve("library/packages/demo/1.0.0/demo.jar");
        Files.createDirectories(installed.getParent());
        Files.writeString(installed, "installed");
        Path status = managerDir.resolve("library/status");
        Files.writeString(status, "status");
        Path journal = managerDir.resolve("library/journal/op.log");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "journal");
        Path trash = managerDir.resolve("library/trash/op/old.jar");
        Files.createDirectories(trash.getParent());
        Files.writeString(trash, "trash");

        cache.clean();

        assertFalse(Files.exists(managerDir.resolve("library/cache/downloads").resolve(sha256(content))));
        assertTrue(Files.exists(installed));
        assertTrue(Files.exists(status));
        assertTrue(Files.exists(journal));
        assertTrue(Files.exists(trash));
    }

    private PackageCache cache(Path managerDir) {
        return new PackageCache(managerDir, new OkHttpClient(), logger);
    }

    private RepositoryManager.PackageCandidate candidate(byte[] content, String url) throws Exception {
        return new RepositoryManager.PackageCandidate("demo", "1.0.0", "stable", "test",
                url, content.length, sha256(content), 0);
    }

    private String serve(byte[] content, AtomicBoolean sawRange) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pkg.zip", exchange -> handle(exchange, content, sawRange));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/pkg.zip";
    }

    private static void handle(HttpExchange exchange, byte[] content, AtomicBoolean sawRange)
            throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=") && range.endsWith("-")) {
            sawRange.set(true);
            int start = Integer.parseInt(range.substring("bytes=".length(),
                    range.length() - 1));
            byte[] slice = java.util.Arrays.copyOfRange(content, start, content.length);
            exchange.getResponseHeaders().add("Content-Range",
                    "bytes " + start + "-" + (content.length - 1) + "/" + content.length);
            exchange.sendResponseHeaders(206, slice.length);
            exchange.getResponseBody().write(slice);
        } else {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
        exchange.close();
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
