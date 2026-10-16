package me.majhrs16.suite.logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Ultra-low-overhead unified logger for the TextFormatter Suite ecosystem.
 * <p>
 * Features:
 * <ul>
 *   <li><b>Zero-overhead debug logging:</b> {@link #debug(String, Object...)} calls are eliminated by JIT
 *       when {@link DebugConfig#DEBUG} is {@code false} (set at compile time via {@code -Plogger.debug=true})</li>
 *   <li><b>Runtime levels:</b> {@code info}, {@code warn}, {@code error} always active</li>
 *   <li><b>Multi-destination output:</b> Console, plugin directory ({@code <server>/plugins/<manager>/suite.log}),
 *       fallback temp file ({@code /tmp/TextFormatter-Suite.log})</li>
 *   <li><b>Per-module formatters:</b> Each module can register a custom formatter for structured logging</li>
 * </ul>
 * </p>
 * <p>
 * Usage:
 * <pre>{@code
 * // Debug (zero overhead when disabled)
 * Logger.debug("VM cycle: IP=0x%04X OP=%s", ip, Opcode.name(opcode));
 *
 * // Runtime levels (always active)
 * Logger.info("Plugin loaded: %s", pluginName);
 * Logger.warn("Rate limit approached: %d/%d", current, limit);
 * Logger.error("Connection failed: %s", e.getMessage(), e);
 * }</pre>
 * </p>
 */
public final class Logger {

    private static final String LOG_FILE_NAME = "suite.log";
    private static final String TEMP_LOG_PATH = "/tmp/TextFormatter-Suite.log";
    private static final String PLUGIN_DIR_PROP = "suite.plugin.dir";
    private static final String MANAGER_NAME_PROP = "suite.manager.name";

    private static volatile PrintWriter writer;
    private static volatile boolean initialized = false;
    private static volatile boolean useStdout = true;

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                         .withZone(ZoneId.systemDefault());

    // Per-module formatters for custom structured logging
    private static final ConcurrentMap<String, ModuleFormatter> formatters = new ConcurrentHashMap<>();

    private Logger() {}

    /**
     * Registers a custom formatter for a module.
     * @param moduleName the module identifier (e.g., "iflow", "gtranslate")
     * @param formatter the formatter instance
     */
    public static void registerFormatter(String moduleName, ModuleFormatter formatter) {
        formatters.put(moduleName, formatter);
    }

    /**
     * Unregisters a module formatter.
     * @param moduleName the module identifier
     */
    public static void unregisterFormatter(String moduleName) {
        formatters.remove(moduleName);
    }

    /**
     * Logs a debug message with formatted parameters.
     * <p>
     * <b>Zero overhead when {@link DebugConfig#DEBUG} is false.</b>
     * The JIT compiler eliminates this call entirely at runtime.
     * </p>
     * @param format the format string (printf-style)
     * @param args format arguments
     */
    public static void debug(String format, Object... args) {
        if (!DebugConfig.DEBUG) return;
        init();
        String message = String.format(Locale.ROOT, format, args);
        writer.println(formatLog("DEBUG", null, message));
    }

    /**
     * Logs a debug message with a module-specific formatter.
     * <p>
     * <b>Zero overhead when {@link DebugConfig#DEBUG} is false.</b>
     * </p>
     * @param moduleName the module identifier (must have registered formatter)
     * @param context arbitrary context object passed to formatter
     * @param format the format string
     * @param args format arguments
     */
    public static void debug(String moduleName, Object context, String format, Object... args) {
        if (!DebugConfig.DEBUG) return;
        init();
        ModuleFormatter formatter = formatters.get(moduleName);
        String message = formatter != null
            ? formatter.formatDebug(context, format, args)
            : String.format(Locale.ROOT, format, args);
        writer.println(formatLog("DEBUG", moduleName, message));
    }

    /**
     * Logs a debug message using a supplier (lazy evaluation).
     * <p>
     * <b>Zero overhead when {@link DebugConfig#DEBUG} is false.</b>
     * The supplier is never invoked when debug is disabled.
     * </p>
     * @param messageSupplier supplies the message (only called if debug enabled)
     */
    public static void debug(Supplier<String> messageSupplier) {
        if (!DebugConfig.DEBUG) return;
        init();
        writer.println(formatLog("DEBUG", null, messageSupplier.get()));
    }

    /**
     * Logs an info message (always active).
     * @param format the format string
     * @param args format arguments
     */
    public static void info(String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        writer.println(formatLog("INFO", null, message));
    }

    /**
     * Logs an info message with module context.
     * @param moduleName the module identifier
     * @param format the format string
     * @param args format arguments
     */
    public static void info(String moduleName, String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        writer.println(formatLog("INFO", moduleName, message));
    }

    /**
     * Logs a warning message (always active).
     * @param format the format string
     * @param args format arguments
     */
    public static void warn(String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        writer.println(formatLog("WARN", null, message));
    }

    /**
     * Logs a warning message with module context.
     * @param moduleName the module identifier
     * @param format the format string
     * @param args format arguments
     */
    public static void warn(String moduleName, String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        writer.println(formatLog("WARN", moduleName, message));
    }

    /**
     * Logs an error message (always active).
     * @param format the format string
     * @param args format arguments
     */
    public static void error(String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        writer.println(formatLog("ERROR", null, message));
    }

    /**
     * Logs an error message with module context.
     * @param moduleName the module identifier
     * @param format the format string
     * @param args format arguments
     */
    public static void error(String moduleName, String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        writer.println(formatLog("ERROR", moduleName, message));
    }

    /**
     * Logs an error message with exception (always active).
     * @param format the format string
     * @param throwable the exception
     * @param args format arguments
     */
    public static void error(Throwable throwable, String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        writer.println(formatLog("ERROR", null, message + "\n" + sw));
    }

    /**
     * Logs an error message with exception and module context.
     * @param moduleName the module identifier
     * @param throwable the exception
     * @param format the format string
     * @param args format arguments
     */
    public static void error(String moduleName, Throwable throwable, String format, Object... args) {
        init();
        String message = String.format(Locale.ROOT, format, args);
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        writer.println(formatLog("ERROR", moduleName, message + "\n" + sw));
    }

    /**
     * Initializes the logger with the best available destination.
     * Priority: plugin directory > temp file > stdout
     */
    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        // Try plugin directory
        String pluginDir = System.getProperty(PLUGIN_DIR_PROP);
        String managerName = System.getProperty(MANAGER_NAME_PROP, "manager");

        if (pluginDir != null && !pluginDir.isBlank()) {
            File logFile = new File(pluginDir, "plugins/" + managerName + "/" + LOG_FILE_NAME);
            try {
                logFile.getParentFile().mkdirs();
                writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)), true);
                useStdout = false;
                logInternal("[Logger] Initialized plugin file logging: " + logFile.getAbsolutePath());
                return;
            } catch (IOException e) {
                // Fall through
            }
        }

        // Try temp file
        try {
            File tempFile = new File(TEMP_LOG_PATH);
            writer = new PrintWriter(new BufferedWriter(new FileWriter(tempFile, true)), true);
            useStdout = false;
            logInternal("[Logger] Initialized temp file logging: " + TEMP_LOG_PATH);
            return;
        } catch (IOException e) {
            // Fall through
        }

        // Default to stdout
        writer = new PrintWriter(System.out, true);
        useStdout = true;
        logInternal("[Logger] Initialized stdout logging");
    }

    private static String formatLog(String level, String module, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append(TIMESTAMP_FORMAT.format(Instant.now()));
        sb.append(" [").append(level).append("]");
        if (module != null) {
            sb.append(" [").append(module).append("]");
        }
        sb.append(" ").append(message);
        return sb.toString();
    }

    private static void logInternal(String message) {
        writer.println(formatLog("INFO", null, message));
    }

    /**
     * Functional interface for module-specific debug formatting.
     * Allows modules like iflow to provide custom structured output for debug logs.
     */
    @FunctionalInterface
    public interface ModuleFormatter {
        /**
         * Formats a debug message for the module.
         * @param context module-specific context (e.g., VM state snapshot)
         * @param format the format string
         * @param args format arguments
         * @return formatted message
         */
        String formatDebug(Object context, String format, Object... args);
    }
}