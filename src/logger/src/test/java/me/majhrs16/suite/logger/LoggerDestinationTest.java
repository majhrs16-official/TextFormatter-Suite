package me.majhrs16.suite.logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the destination resolution performed once by {@code Logger.init()}:
 * plugin file &gt; temp fallback &gt; stdout, and the dev/test stdout default.
 */
class LoggerDestinationTest {

    @BeforeEach
    void setUp() {
        LogTestSupport.resetState();
    }

    @AfterEach
    void tearDown() {
        LogTestSupport.resetState();
    }

    @Test
    void withoutPluginDirLogsToStdout() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            Logger.info("to stdout");
        } finally {
            System.setOut(original);
        }

        assertTrue(LogTestSupport.<Boolean>getStatic("useStdout"));
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("[INFO] to stdout"));
    }

    @Test
    void blankPluginDirIsTreatedAsDevMode() throws Exception {
        System.setProperty(LogTestSupport.PLUGIN_DIR_PROP, "   ");
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            Logger.info("blank dir");
        } finally {
            System.setOut(original);
        }

        assertTrue(LogTestSupport.<Boolean>getStatic("useStdout"));
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("[INFO] blank dir"));
    }

    @Test
    void withPluginDirWritesToSuiteLogFile(@TempDir Path tmp) throws Exception {
        System.setProperty(LogTestSupport.PLUGIN_DIR_PROP, tmp.toString());
        System.setProperty(LogTestSupport.MANAGER_NAME_PROP, "testmgr");

        Logger.info("to plugin file");

        Path logFile = tmp.resolve("plugins/testmgr/suite.log");
        assertTrue(Files.exists(logFile), "expected log file at " + logFile);
        assertFalse(LogTestSupport.<Boolean>getStatic("useStdout"));
        assertTrue(Files.readString(logFile).contains("[INFO] to plugin file"));

        LogTestSupport.closeFileWriter();
    }

    @Test
    void unwritablePluginDirFallsBackToTempFile(@TempDir Path tmp) throws Exception {
        // Point the plugin dir under a regular file so mkdirs()/FileWriter fail.
        Path blocker = Files.createFile(tmp.resolve("blocker"));
        System.setProperty(LogTestSupport.PLUGIN_DIR_PROP, blocker.resolve("sub").toString());
        System.setProperty(LogTestSupport.MANAGER_NAME_PROP, "testmgr");

        Path tempLog = Path.of("/tmp/TextFormatter-Suite.log");
        boolean existed = Files.exists(tempLog);

        Logger.info("to temp fallback");

        assertFalse(LogTestSupport.<Boolean>getStatic("useStdout"));
        assertTrue(Files.exists(tempLog));
        assertTrue(Files.readString(tempLog).contains("[INFO] to temp fallback"));

        LogTestSupport.closeFileWriter();
        if (!existed) {
            Files.deleteIfExists(tempLog);
        }
    }
}
