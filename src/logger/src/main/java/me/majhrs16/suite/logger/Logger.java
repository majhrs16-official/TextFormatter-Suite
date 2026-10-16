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
 *
 * // Module-tagged output (see LoggerUtils.Module)
 * LoggerUtils.Module.info("iflow", "Loaded %d programs", count);
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

    private Logger() {}

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
     * Initializes the logger with the best available destination.
     * Priority: plugin directory > temp file > stdout
     */
    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        String pluginDir = System.getProperty(PLUGIN_DIR_PROP);
        String managerName = System.getProperty(MANAGER_NAME_PROP, "manager");

        // Production mode: a plugin directory is configured.
        // Priority: plugin log file -> temp fallback -> stdout.
        if (pluginDir != null && !pluginDir.isBlank()) {
            File logFile = new File(pluginDir, "plugins/" + managerName + "/" + LOG_FILE_NAME);
            if (tryOpenFile(logFile)) {
                useStdout = false;
                logInternal("[Logger] Initialized plugin file logging: " + logFile.getAbsolutePath());
                return;
            }
            // Plugin file failed: fall back to temp file (server-side backup).
            if (tryOpenFile(new File(TEMP_LOG_PATH))) {
                useStdout = false;
                logInternal("[Logger] Initialized temp file logging (plugin dir unavailable): " + TEMP_LOG_PATH);
                return;
            }
            // Last resort: stdout
            openStdout();
            logInternal("[Logger] Initialized stdout logging (all file destinations failed)");
            return;
        }

        // Development / test mode: no plugin dir configured -> console output.
        openStdout();
        logInternal("[Logger] Initialized stdout logging (dev/test mode)");
    }

    private static boolean tryOpenFile(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            writer = new PrintWriter(new BufferedWriter(new FileWriter(file, true)), true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void openStdout() {
        writer = new PrintWriter(System.out, true);
        useStdout = true;
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
}