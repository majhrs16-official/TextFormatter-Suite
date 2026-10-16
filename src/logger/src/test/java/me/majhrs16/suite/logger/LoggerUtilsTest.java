package me.majhrs16.suite.logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link LoggerUtils.Module}, the module-scoped entry points that tag
 * every line with {@code [module]}.
 * <p>
 * The debug cases adapt to the build exactly like {@link LoggerTest}: the default
 * build ({@code DEBUG=false}) asserts that nothing is emitted and that lazy
 * suppliers never run; {@code -Plogger.debug=true} exercises the emitting side.
 * </p>
 */
class LoggerUtilsTest {

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

    // --------------------------------------------------------------- always-on

    @Test
    void infoTagsWithModuleName() {
        LoggerUtils.Module.info("iflow", "loaded %d programs", 3);
        assertTrue(output().contains("[INFO] [iflow] loaded 3 programs"), output());
    }

    @Test
    void warnTagsWithModuleName() {
        LoggerUtils.Module.warn("transport", "retry %d/%d", 2, 5);
        assertTrue(output().contains("[WARN] [transport] retry 2/5"), output());
    }

    @Test
    void errorTagsWithModuleName() {
        LoggerUtils.Module.error("sync-bus", "peer %s gone", "alpha");
        assertTrue(output().contains("[ERROR] [sync-bus] peer alpha gone"), output());
    }

    @Test
    void errorWithThrowableTagsAndAppendsStackTrace() {
        LoggerUtils.Module.error("iflow", new IllegalStateException("kaboom"), "trap at %d", 7);
        String s = output();
        assertTrue(s.contains("[ERROR] [iflow] trap at 7"), s);
        assertTrue(s.contains("java.lang.IllegalStateException: kaboom"), s);
        assertTrue(s.contains("\tat "), s);
    }

    // ------------------------------------------------------------- debug gated

    @Test
    void debugEmitsNothingWhenCompiledOut() {
        assumeFalse(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        LoggerUtils.Module.debug("iflow", "VM_CYCLE %d", 1);
        assertEquals("", output());
    }

    @Test
    void debugSupplierIsNotEvaluatedWhenCompiledOut() {
        assumeFalse(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        AtomicBoolean evaluated = new AtomicBoolean(false);
        LoggerUtils.Module.debug("iflow", () -> {
            evaluated.set(true);
            return "VM_CYCLE";
        });
        assertFalse(evaluated.get(), "supplier must not run when DEBUG is false");
        assertEquals("", output());
    }

    @Test
    void debugTagsWithModuleNameWhenEnabled() {
        assumeTrue(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        LoggerUtils.Module.debug("iflow", "VM_CYCLE %d", 1);
        assertTrue(output().contains("[DEBUG] [iflow] VM_CYCLE 1"), output());
    }

    @Test
    void debugSupplierIsEvaluatedAndTaggedWhenEnabled() {
        assumeTrue(DebugConfig.DEBUG, "compile with -Plogger.debug=true to exercise debug output");
        AtomicBoolean evaluated = new AtomicBoolean(false);
        LoggerUtils.Module.debug("iflow", () -> {
            evaluated.set(true);
            return "lazy cycle";
        });
        assertTrue(evaluated.get(), "supplier must run when DEBUG is true");
        assertTrue(output().contains("[DEBUG] [iflow] lazy cycle"), output());
    }
}
