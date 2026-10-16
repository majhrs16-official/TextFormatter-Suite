package me.majhrs16.suite.logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Behavioural tests for the {@link Logger} line format, the always-on runtime
 * levels, the compile-time gated debug path and the module-formatter registry.
 * <p>
 * The debug-specific cases adapt to the build: with the default build
 * ({@code DEBUG=false}) they assert that nothing is emitted and lazy suppliers
 * are not evaluated; run with {@code -Plogger.debug=true} to exercise the
 * emitting side instead.
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
        // Cast to Object so the call binds to info(String, Object...), not the
        // more specific info(String module, String format, Object...) overload.
        Logger.info("hello %s", (Object) "world");
        assertTrue(output().trim().matches(
                "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[INFO\\] hello world"),
                output());
    }

    @Test
    void infoWithModuleAddsModuleTag() {
        Logger.info("mymod", "loaded %d", 7);
        assertTrue(output().contains("[INFO] [mymod] loaded 7"), output());
    }

    @Test
    void warnLevelIsAlwaysActive() {
        Logger.warn("disk %d%%", 90);
        assertTrue(output().contains("[WARN] disk 90%"), output());
    }

    @Test
    void warnWithModuleAddsModuleTag() {
        Logger.warn("mymod", "slow response");
        assertTrue(output().contains("[WARN] [mymod] slow response"), output());
    }

    @Test
    void errorLevelIsAlwaysActive() {
        // Cast to Object to avoid binding to the module overload (see above).
        Logger.error("failed: %s", (Object) "boom");
        assertTrue(output().contains("[ERROR] failed: boom"), output());
    }

    @Test
    void errorWithModuleAddsModuleTag() {
        Logger.error("mymod", "failed");
        assertTrue(output().contains("[ERROR] [mymod] failed"), output());
    }

    @Test
    void errorWithThrowableAppendsStackTrace() {
        Logger.error(new IllegalStateException("kaboom"), "op failed");
        String s = output();
        assertTrue(s.contains("[ERROR] op failed"), s);
        assertTrue(s.contains("java.lang.IllegalStateException: kaboom"), s);
        assertTrue(s.contains("\tat "), s);
    }

    @Test
    void errorWithModuleAndThrowable() {
        Logger.error("mymod", new RuntimeException("nope"), "op failed");
        String s = output();
        assertTrue(s.contains("[ERROR] [mymod] op failed"), s);
        assertTrue(s.contains("java.lang.RuntimeException: nope"), s);
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
    void debugUsesRegisteredModuleFormatterWhenEnabled() {
        assumeTrue(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        Logger.registerFormatter("iflow", (ctx, fmt, args) -> "CTX[" + ctx + "] " + String.format(fmt, args));
        try {
            Logger.debug("iflow", "snap", "IP=0x%04X", 0x1000);
            assertTrue(output().contains("[DEBUG] [iflow] CTX[snap] IP=0x1000"), output());
        } finally {
            Logger.unregisterFormatter("iflow");
        }
    }

    @Test
    void debugFallsBackToPlainFormatWhenNoFormatterRegistered() {
        assumeTrue(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        Logger.debug("nomod", "snap", "x=%d", 1);
        assertTrue(output().contains("[DEBUG] [nomod] x=1"), output());
    }

    @Test
    void debugSupplierEmitsWhenEnabled() {
        assumeTrue(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        Logger.debug(() -> "lazy value");
        assertTrue(output().contains("[DEBUG] lazy value"), output());
    }

    // ------------------------------------------------------ formatter registry

    @Test
    void formattersCanBeRegisteredAndUnregistered() {
        Map<String, Logger.ModuleFormatter> formatters = LogTestSupport.getStatic("formatters");
        Logger.ModuleFormatter formatter = (ctx, fmt, args) -> "x";
        Logger.registerFormatter("reg", formatter);
        assertSame(formatter, formatters.get("reg"));
        Logger.unregisterFormatter("reg");
        assertFalse(formatters.containsKey("reg"));
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
