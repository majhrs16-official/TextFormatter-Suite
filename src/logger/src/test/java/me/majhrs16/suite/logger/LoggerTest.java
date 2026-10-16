package me.majhrs16.suite.logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Behavioural tests for the {@link Logger} line format, the always-on runtime
 * levels and the compile-time gated debug path.
 * <p>
 * The debug-specific cases adapt to the build: with the default build
 * ({@code DEBUG=false}) they assert that nothing is emitted and lazy suppliers
 * are not evaluated; run with {@code -Plogger.debug=true} to exercise the
 * emitting side instead. Module-tagged logging is covered by
 * {@link LoggerUtilsTest}.
 * </p>
 */
class LoggerTest {

    private StringWriter out;

    @BeforeEach
    void setUp() {
        LogTestSupport.resetState();
        out = LogTestSupport.installCapture();
    }

    @AfterEach
    void tearDown() {
        LogTestSupport.resetState();
    }

    private String output() {
        return out.toString();
    }

    // ---------------------------------------------------------------- format

    @Test
    void infoLineHasTimestampLevelAndMessage() {
        Logger.info("hello %s", "world");
        assertTrue(output().trim().matches(
                "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[INFO\\] hello world"),
                output());
    }

    @Test
    void warnLevelIsAlwaysActive() {
        Logger.warn("disk %d%%", 90);
        assertTrue(output().contains("[WARN] disk 90%"), output());
    }

    @Test
    void errorLevelIsAlwaysActive() {
        Logger.error("failed: %s", "boom");
        assertTrue(output().contains("[ERROR] failed: boom"), output());
    }

    @Test
    void errorWithThrowableAppendsStackTrace() {
        Logger.error(new IllegalStateException("kaboom"), "op failed");
        String s = output();
        assertTrue(s.contains("[ERROR] op failed"), s);
        assertTrue(s.contains("java.lang.IllegalStateException: kaboom"), s);
        assertTrue(s.contains("\tat "), s);
    }

    // ---------------------------------------------------- compile-time gating

    @Test
    void debugEmitsNothingWhenCompiledOut() {
        assumeFalse(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        Logger.debug("invisible %d", 42);
        assertEquals("", output());
    }

    @Test
    void debugSupplierIsNotEvaluatedWhenCompiledOut() {
        assumeFalse(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        AtomicBoolean evaluated = new AtomicBoolean(false);
        Logger.debug(() -> {
            evaluated.set(true);
            return "should never be produced";
        });
        assertFalse(evaluated.get(), "supplier must not run when DEBUG is false");
        assertEquals("", output());
    }

    @Test
    void debugSupplierEmitsWhenEnabled() {
        assumeTrue(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        Logger.debug(() -> "lazy value");
        assertTrue(output().contains("[DEBUG] lazy value"), output());
    }

    // ------------------------------------------------------ config contract

    @Test
    void debugFlagIsACompileTimeConstant() throws Exception {
        Field field = DebugConfig.class.getField("DEBUG");
        assertTrue(Modifier.isStatic(field.getModifiers()), "DEBUG must be static");
        assertTrue(Modifier.isFinal(field.getModifiers()), "DEBUG must be final");
        assertEquals(boolean.class, field.getType());
    }
}
