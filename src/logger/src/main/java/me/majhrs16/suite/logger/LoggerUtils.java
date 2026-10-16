package me.majhrs16.suite.logger;

import java.util.function.Supplier;

/**
 * Convenience helpers built on top of {@link Logger}.
 * <p>
 * The module-scoped entry points live here, in a separate class from the
 * format-only {@link Logger} API. Keeping them apart is what removes the
 * overload ambiguity: a call such as {@code info("loaded: %s", name)} binds
 * unambiguously to {@code Logger.info(String, Object...)}, while
 * {@link Module#info(String, String, Object...)} adds the module tag.
 * </p>
 */
public final class LoggerUtils {

    private LoggerUtils() {}

    /**
     * Module-scoped logging. Every line is tagged with the module name,
     * producing {@code <timestamp> [LEVEL] [module] message}.
     */
    public static final class Module {

        private Module() {}

        /**
         * Logs an info message for a module.
         * @param module the module identifier (e.g. {@code "iflow"})
         * @param format the format string
         * @param args format arguments
         */
        public static void info(String module, String format, Object... args) {
            Logger.info(tag(module, format), args);
        }

        /**
         * Logs a warning message for a module.
         * @param module the module identifier
         * @param format the format string
         * @param args format arguments
         */
        public static void warn(String module, String format, Object... args) {
            Logger.warn(tag(module, format), args);
        }

        /**
         * Logs an error message for a module.
         * @param module the module identifier
         * @param format the format string
         * @param args format arguments
         */
        public static void error(String module, String format, Object... args) {
            Logger.error(tag(module, format), args);
        }

        /**
         * Logs an error message for a module, appending the stack trace.
         * @param module the module identifier
         * @param throwable the exception
         * @param format the format string
         * @param args format arguments
         */
        public static void error(String module, Throwable throwable, String format, Object... args) {
            Logger.error(throwable, tag(module, format), args);
        }

        /**
         * Logs a debug message for a module.
         * <p>
         * The {@link DebugConfig#DEBUG} guard lives here, inside the logger:
         * call sites never check it. Zero overhead when debug is compiled out —
         * the JIT eliminates the whole call.
         * </p>
         * @param module the module identifier
         * @param format the format string
         * @param args format arguments
         */
        public static void debug(String module, String format, Object... args) {
            if (!DebugConfig.DEBUG) return;
            Logger.debug(tag(module, format), args);
        }

        /**
         * Logs a lazily-built debug message for a module.
         * <p>
         * Use this overload when building the message is expensive (e.g. a VM
         * state dump): the supplier is only invoked when
         * {@link DebugConfig#DEBUG} is {@code true}, so nothing is computed in
         * production builds. The guard lives here, inside the logger, exactly as
         * in {@link #debug(String, String, Object...)}.
         * </p>
         * @param module the module identifier
         * @param messageSupplier supplies the already-formatted message
         */
        public static void debug(String module, Supplier<String> messageSupplier) {
            if (!DebugConfig.DEBUG) return;
            Logger.debug(() -> tag(module, messageSupplier.get()));
        }

        private static String tag(String module, String format) {
            return "[" + module + "] " + format;
        }
    }
}
