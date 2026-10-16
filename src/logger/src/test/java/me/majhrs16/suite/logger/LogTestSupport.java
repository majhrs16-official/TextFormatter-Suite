package me.majhrs16.suite.logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;

/**
 * Reflection helpers to drive the static {@link Logger} singleton from tests.
 * <p>
 * {@code Logger} is a process-wide singleton whose destination is decided once,
 * on first use, and whose {@code DEBUG} flag is baked in at compile time. To test
 * every branch we must reset its private static state and, for output-capturing
 * tests, inject a writer. All of that lives here so the test classes stay readable.
 * </p>
 */
final class LogTestSupport {

    static final String PLUGIN_DIR_PROP = "suite.plugin.dir";
    static final String MANAGER_NAME_PROP = "suite.manager.name";

    private LogTestSupport() {}

    static void setStatic(String name, Object value) {
        try {
            Field field = Logger.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set Logger." + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T getStatic(String name) {
        try {
            Field field = Logger.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read Logger." + name, e);
        }
    }

    /** Returns {@link Logger} to a pristine, uninitialized state with no configured destination. */
    static void resetState() {
        setStatic("writer", null);
        setStatic("initialized", false);
        setStatic("useStdout", true);
        System.clearProperty(PLUGIN_DIR_PROP);
        System.clearProperty(MANAGER_NAME_PROP);
    }

    /**
     * Injects a capturing writer and marks the logger as initialized, so calls write
     * to the returned buffer instead of stdout or a file. Does not touch system state.
     */
    static StringWriter installCapture() {
        StringWriter sw = new StringWriter();
        setStatic("writer", new PrintWriter(sw, true));
        setStatic("initialized", true);
        setStatic("useStdout", true);
        return sw;
    }

    /**
     * Closes a file-backed writer to release its handle. Never call this while the
     * logger is writing to stdout, or it would close {@code System.out}.
     */
    static void closeFileWriter() {
        PrintWriter writer = getStatic("writer");
        if (writer != null) {
            writer.close();
        }
        setStatic("writer", null);
        setStatic("initialized", false);
    }
}
