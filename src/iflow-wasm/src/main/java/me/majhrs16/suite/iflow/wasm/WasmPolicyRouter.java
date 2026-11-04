package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.wasm.WasmModule;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RateLimiter;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Router respaldado por una política WebAssembly v3.
 *
 * <p>Carga {@code policy.wasm} desde el directorio de configuración del
 * módulo y exige un SHA-256 en {@code policy.sha256} o
 * {@code policy.wasm.sha256}. El hash es obligatorio: sin él, o si no coincide,
 * el router falla cerrado y no evalúa la política. El manager ya verifica el
 * SHA-256 del ZIP/JAR del paquete; este hash cubre el artefacto de política
 * que el usuario instala en {@code config/iflow-wasm/}.</p>
 *
 * <p>El artefacto se recarga automáticamente al cambiar el {@code .wasm} o su
 * sidecar. Un artefacto nuevo inválido desactiva el backend hasta que se
 * corrija; no se conserva silenciosamente la política anterior.</p>
 */
public final class WasmPolicyRouter implements Router, AutoCloseable {

    private static final String POLICY_FILE = "policy.wasm";
    private static final Pattern SHA256 = Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");
    private static final long WATCH_DEBOUNCE_MILLIS = 100L;

    private final Path configDir;
    private final Path wasmFile;
    private final List<Path> hashFiles;
    private final ChannelRegistry channels;
    private final PermissionChecker permissions;
    private final PlaceholderResolver placeholders;
    private final Function<String, Language> languageDetector;
    private final PluginLogger logger;
    private final WasmModuleLoader loader;
    private final WasmPolicyEngine engine;
    private final WasmContract contract;
    private final RateLimiter rateLimiter;

    private volatile WasmModule module;
    private volatile String lastError = "not loaded";
    private final CountDownLatch watcherReady = new CountDownLatch(1);
    private volatile boolean closed;
    private Thread watcher;

    public WasmPolicyRouter(Path configDir,
                            ChannelRegistry channels,
                            PermissionChecker permissions,
                            PlaceholderResolver placeholders,
                            Function<String, Language> languageDetector,
                            PluginLogger logger) {
        this(configDir, channels, permissions, placeholders, languageDetector, logger,
                new WasmModuleLoader(), new RateLimiter());
    }

    WasmPolicyRouter(Path configDir,
                     ChannelRegistry channels,
                     PermissionChecker permissions,
                     PlaceholderResolver placeholders,
                     Function<String, Language> languageDetector,
                     PluginLogger logger,
                     WasmModuleLoader loader,
                     RateLimiter rateLimiter) {
        this.configDir = Objects.requireNonNull(configDir, "configDir").toAbsolutePath().normalize();
        this.wasmFile = this.configDir.resolve(POLICY_FILE);
        this.hashFiles = List.of(
                this.configDir.resolve("policy.sha256"),
                this.configDir.resolve("policy.wasm.sha256"));
        this.channels = Objects.requireNonNull(channels, "channels");
        this.permissions = permissions == null ? PermissionChecker.ALLOW_ALL : permissions;
        this.placeholders = placeholders;
        this.languageDetector = languageDetector;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.engine = new WasmPolicyEngine(loader);
        this.contract = WasmContract.policyV3();
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        try {
            Files.createDirectories(this.configDir);
        } catch (IOException e) {
            logger.warn("Cannot create iFlow Wasm config directory " + this.configDir
                    + ": " + e.getMessage());
        }
        reload();
        startWatcher();
    }

    /**
     * Recarga la política desde disco. Es público para que el runtime o un
     * comando administrativo puedan forzar la recarga sin esperar al watcher.
     *
     * @return {@code true} si hay una política válida y verificada
     */
    public synchronized boolean reload() {
        try {
            if (!Files.isRegularFile(wasmFile)) {
                unavailable("policy.wasm no encontrado en " + configDir);
                return false;
            }
            byte[] bytes = Files.readAllBytes(wasmFile);
            String expected = expectedHash();
            if (expected == null) {
                unavailable("falta SHA-256 de policy.wasm (policy.sha256 o policy.wasm.sha256)");
                return false;
            }
            String actual = sha256(bytes);
            if (!actual.equalsIgnoreCase(expected)) {
                unavailable("SHA-256 de policy.wasm no coincide: esperado "
                        + expected + ", obtenido " + actual);
                return false;
            }
            WasmModule parsed = loader.load(bytes);
            ValidationResult validation = loader.validate(parsed, contract);
            if (!validation.valid()) {
                unavailable("artefacto Wasm inválido: " + validation.errors());
                return false;
            }
            module = parsed;
            lastError = "";
            logger.info("iFlow Wasm policy loaded: " + wasmFile + " (" + actual + ")");
            return true;
        } catch (IOException | RuntimeException e) {
            unavailable("no se pudo cargar policy.wasm: " + message(e));
            return false;
        }
    }

    /** @return {@code true} si hay una política verificada y validada. */
    public boolean isAvailable() {
        return module != null;
    }

    /** @return último error de carga/verificación, o cadena vacía. */
    public String lastError() {
        return lastError;
    }

    @Override
    public RouteOutcome route(Message message, Actor recipient) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(recipient, "recipient");
        Actor emitter = message.sender() == null ? Actor.unknown("console") : message.sender();
        String path = message.channel() == null || message.channel().isBlank()
                ? "chat" : message.channel();
        Channel channel = channels.resolve(path);

        if (!recipient.equals(emitter)
                && !allowed(emitter, channel.sendPolicy())) {
            return outcome(PolicyTarget.REJECT,
                    "emitter lacks send permission on " + path, recipient, emitter, message);
        }
        if (!allowed(recipient, channel.receivePolicy())) {
            return outcome(PolicyTarget.DROP,
                    "recipient lacks receive permission on " + path, recipient, emitter, message);
        }

        WasmModule current = module;
        if (current == null) {
            return outcome(PolicyTarget.REJECT,
                    "iflow-wasm unavailable: " + lastError, recipient, emitter, message);
        }
        try {
            PolicyRequest request = new PolicyRequest(
                    message, emitter, recipient, channels, permissions, placeholders, languageDetector);
            PolicyOutcome result = engine.evaluate(current, request);
            PolicyTarget target = switch (result.action()) {
                case DELIVER -> PolicyTarget.LOG;
                case CANCEL_EVENT -> PolicyTarget.DROP;
                case ERROR -> PolicyTarget.REJECT;
            };
            String reason = result.reason();
            if (reason == null || reason.isBlank()) {
                reason = result.target();
            }
            return outcome(target, reason, recipient, emitter, result.message());
        } catch (RuntimeException e) {
            return outcome(PolicyTarget.REJECT,
                    "iflow-wasm internal error: " + message(e), recipient, emitter, message);
        }
    }

    @Override
    public boolean hasPermission(Actor actor, String permission) {
        return permissions.has(actor, permission);
    }

    @Override
    public boolean checkEmissionRateLimit(Message message) {
        Objects.requireNonNull(message, "message");
        String path = message.channel() == null || message.channel().isBlank()
                ? "chat" : message.channel();
        Channel channel = channels.resolve(path);
        Actor emitter = message.sender() == null ? Actor.unknown("console") : message.sender();
        if (channel.rateLimitPerSecond() > 0) {
            String key = path + "\u0000" + emitter.uuid();
            return rateLimiter.tryAcquire(key, channel.rateLimitPerSecond());
        }
        return true;
    }

    @Override
    public long nanosUntilNextRateLimitWindow(Message message) {
        Objects.requireNonNull(message, "message");
        String path = message.channel() == null || message.channel().isBlank()
                ? "chat" : message.channel();
        Channel channel = channels.resolve(path);
        Actor emitter = message.sender() == null ? Actor.unknown("console") : message.sender();
        if (channel.rateLimitPerSecond() > 0) {
            String key = path + "\u0000" + emitter.uuid();
            return rateLimiter.nanosUntilNextWindow(key);
        }
        return 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Thread thread = watcher;
        watcher = null;
        if (thread != null) {
            thread.interrupt();
        }
        rateLimiter.close();
    }

    private void startWatcher() {
        Thread thread = new Thread(this::watch, "iflow-wasm-watch");
        thread.setDaemon(true);
        watcher = thread;
        thread.start();
    }

    private void watch() {
        Set<String> watchedNames = Set.of(
                wasmFile.getFileName().toString(),
                hashFiles.get(0).getFileName().toString(),
                hashFiles.get(1).getFileName().toString());
        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            configDir.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watcherReady.countDown();
            while (!closed) {
                WatchKey key;
                try {
                    key = service.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (key == null) {
                    continue;
                }
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        relevant = true;
                        continue;
                    }
                    Object context = event.context();
                    if (context instanceof Path changed
                            && watchedNames.contains(changed.getFileName().toString())) {
                        relevant = true;
                    }
                }
                key.reset();
                if (relevant) {
                    Thread.sleep(WATCH_DEBOUNCE_MILLIS);
                    reload();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // close() interrupted the watcher while it was polling.
        } catch (IOException e) {
            if (!closed) {
                logger.warn("iFlow Wasm watcher stopped: " + e.getMessage());
            }
        }
    }

    boolean awaitWatcherReady(long timeout, TimeUnit unit) throws InterruptedException {
        return watcherReady.await(timeout, unit);
    }

    private String expectedHash() throws IOException {
        for (Path hashFile : hashFiles) {
            if (!Files.isRegularFile(hashFile)) {
                continue;
            }
            String content = Files.readString(hashFile).trim();
            Matcher matcher = SHA256.matcher(content);
            if (matcher.find()) {
                return matcher.group().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private void unavailable(String reason) {
        module = null;
        lastError = reason;
        logger.warn("iFlow Wasm unavailable: " + reason);
    }

    private boolean allowed(Actor actor, String permission) {
        return permission == null || permission.isBlank()
                || permissions.has(actor, permission);
    }

    private static RouteOutcome outcome(PolicyTarget target, String reason,
                                        Actor recipient, Actor emitter, Message message) {
        return RouteOutcome.of(new RouteDecision(
                target, reason == null ? "" : reason, 0, recipient, emitter), message);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String message(Throwable failure) {
        String text = failure.getMessage();
        return text == null || text.isBlank() ? failure.getClass().getSimpleName() : text;
    }
}
