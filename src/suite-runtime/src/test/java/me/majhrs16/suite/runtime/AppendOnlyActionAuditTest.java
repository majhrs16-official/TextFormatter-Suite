package me.majhrs16.suite.runtime;

import me.majhrs16.suite.api.sync.ActionAuditRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppendOnlyActionAuditTest {

    private static final String SHA = "a".repeat(64);

    @TempDir
    Path dir;

    private Path log() {
        return dir.resolve("config/iflow/audit.log");
    }

    private static ActionAuditRecord record(String status, String detail) {
        return new ActionAuditRecord(
                Instant.parse("2026-01-02T03:04:05Z"),
                SHA,
                "program:" + SHA,
                "discord",
                "channel.create",
                "c".repeat(64),
                status,
                12,
                detail);
    }

    @Test
    void appendsOneEscapedLinePerRecord() throws Exception {
        AppendOnlyActionAudit audit =
                new AppendOnlyActionAudit(log(), FixtureModules.logger());
        audit.ensureWritable();
        audit.record(record("OK", "detail with | pipe\nnewline\\backslash"));
        audit.record(record("DENIED", ""));
        audit.close();

        List<String> lines = Files.readAllLines(log());
        assertEquals(2, lines.size());
        assertEquals("2026-01-02T03:04:05Z|" + SHA + "|program:" + SHA
                + "|discord|channel.create|" + "c".repeat(64) + "|OK|12|"
                + "detail with \\p pipe\\nnewline\\\\backslash", lines.get(0));
        assertTrue(lines.get(1).endsWith("|DENIED|12|"));
    }

    @Test
    void reopeningAppendsInsteadOfTruncating() throws Exception {
        new AppendOnlyActionAudit(log(), FixtureModules.logger())
                .record(record("OK", "first"));
        new AppendOnlyActionAudit(log(), FixtureModules.logger())
                .record(record("OK", "second"));

        List<String> lines = Files.readAllLines(log());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("|first"));
        assertTrue(lines.get(1).endsWith("|second"));
    }

    @Test
    void ensureWritableCreatesTheParentDirectory() {
        AppendOnlyActionAudit audit =
                new AppendOnlyActionAudit(log(), FixtureModules.logger());
        audit.ensureWritable();

        assertTrue(Files.isRegularFile(log()));
    }

    @Test
    void unwritableTargetFailsExplicitly() throws Exception {
        Files.createDirectories(log());
        AppendOnlyActionAudit audit =
                new AppendOnlyActionAudit(log(), FixtureModules.logger());

        assertThrows(IllegalStateException.class, audit::ensureWritable);
    }

    @Test
    void closeIsIdempotentAndKeepsTheFile() throws Exception {
        AppendOnlyActionAudit audit =
                new AppendOnlyActionAudit(log(), FixtureModules.logger());
        audit.record(record("OK", "kept"));
        audit.close();
        audit.close();

        assertTrue(Files.exists(log()));
        assertFalse(Files.readString(log()).isBlank());
    }
}
