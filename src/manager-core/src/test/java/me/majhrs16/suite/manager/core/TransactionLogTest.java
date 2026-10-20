package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.core.TransactionLog.LogEntry;
import me.majhrs16.suite.manager.core.TransactionLog.OperationState;
import me.majhrs16.suite.manager.core.TransactionLog.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionLogTest {

    @TempDir
    Path tempDir;

    private final PluginLogger logger = new PluginLogger() {
        @Override public void info(String m, Object... a) {}
        @Override public void warn(String m, Object... a) {}
        @Override public void error(String m, Object... a) {}
        @Override public void error(String m, Throwable t) {}
        @Override public void debug(String m, Object... a) {}
    };

    @Test
    void markFailedWithNullMetadataDoesNotWriteNullFields() throws IOException {
        TransactionLog log = new TransactionLog(tempDir, logger);
        log.log("op-1", OperationType.INSTALL, "pkg", "1.0", "stable", OperationState.STARTED, null);
        assertEquals(1, log.getIncompleteOperations().size());

        log.markFailed("op-1", "boom");

        assertTrue(log.getIncompleteOperations().isEmpty());
        String content = Files.readString(tempDir.resolve("library/transactions.log"));
        assertFalse(content.contains("null"), content);
    }

    @Test
    void malformedLinesAreSkippedWithoutLosingValidEntries() throws IOException {
        TransactionLog log = new TransactionLog(tempDir, logger);
        Path file = tempDir.resolve("library/transactions.log");
        Files.writeString(file, """
            this-is-not-a-log-line
            op-2|INSTALL|pkg|1.0|stable|STARTED|2026-10-20T00:00:00Z|details
            op-3|INSTALL|pkg|1.0|stable|NOT_A_STATE|2026-10-20T00:00:00Z|x
            op-4|INSTALL|pkg|1.0|stable|STARTED|not-a-date|x
            """);

        List<LogEntry> incomplete = log.getIncompleteOperations();

        assertEquals(1, incomplete.size());
        assertEquals("op-2", incomplete.get(0).operationId());
    }

    @Test
    void separatorsAndNewlinesInDetailsStayOnOneLine() {
        TransactionLog log = new TransactionLog(tempDir, logger);
        log.log("op-5", OperationType.INSTALL, "pkg", "1.0", "stable", OperationState.STARTED, "a|b\nc");

        LogEntry entry = log.getIncompleteOperations().get(0);

        assertEquals("a/b c", entry.details());
    }
}
