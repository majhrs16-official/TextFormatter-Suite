package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-recovery contract of {@link PackageJournal}.
 *
 * <p>The atomic rename of {@code library/status} is the commit point: before
 * it recovery restores the previous files and status; after it recovery only
 * cleans up and never reverts the installation.</p>
 */
class PackageJournalTest {

    @TempDir
    Path tempDir;

    private final PluginLogger logger = new PluginLogger() {
        @Override public void info(String m, Object... a) { }
        @Override public void warn(String m, Object... a) { }
        @Override public void error(String m, Object... a) { }
        @Override public void error(String m, Throwable t) { }
        @Override public void debug(String m, Object... a) { }
    };

    @Test
    void recoveryBeforeTheStatusCommitRestoresFilesAndStatus() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        PackageJournal journal = PackageJournal.begin(installRoot, database, "before-commit",
                PackageJournal.OperationType.INSTALL, database.currentImage(), image(database));

        Path created = installRoot.resolve("manager/library/packages/demo/1.0.0/demo.jar");
        Files.createDirectories(created.getParent());
        Files.writeString(created, "new", StandardCharsets.UTF_8);
        journal.created(created);

        Path replaced = installRoot.resolve("manager/config/shared.yml");
        Files.createDirectories(replaced.getParent());
        Files.writeString(replaced, "old", StandardCharsets.UTF_8);
        Path backup = database.getBackupDir().resolve("before-commit/manager/config/shared.yml");
        Files.createDirectories(backup.getParent());
        Files.move(replaced, backup);
        Files.writeString(replaced, "new", StandardCharsets.UTF_8);
        journal.replaced(replaced, backup);

        Path removed = installRoot.resolve("manager/library/packages/old/1.0.0/old.jar");
        Files.createDirectories(removed.getParent());
        Files.writeString(removed, "old", StandardCharsets.UTF_8);
        Path trash = database.getTrashDir().resolve("before-commit/manager/library/packages/old/1.0.0/old.jar");
        Files.createDirectories(trash.getParent());
        Files.move(removed, trash);
        journal.removed(removed, trash);

        journal.close();
        PackageJournal.recoverAll(database, installRoot, logger);

        assertFalse(Files.exists(created), "created files must be removed before the commit");
        assertEquals("old", Files.readString(replaced), "replaced files must be restored");
        assertEquals("old", Files.readString(removed), "removed files must be restored");
        assertEquals(database.imageOf(List.of()).sha256(), database.currentStatusSha256());
        assertTrue(Files.readString(database.getJournalDir().resolve("before-commit.log"))
                .contains("ROLLED_BACK"));
    }

    @Test
    void recoveryAfterTheStatusCommitKeepsTheNewInstallation() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        LocalPackageDatabase.StatusImage next = image(database);
        PackageJournal journal = PackageJournal.begin(installRoot, database, "after-commit",
                PackageJournal.OperationType.INSTALL, database.currentImage(), next);

        Path created = installRoot.resolve("manager/library/packages/demo/1.0.0/demo.jar");
        Files.createDirectories(created.getParent());
        Files.writeString(created, "new", StandardCharsets.UTF_8);
        journal.created(created);
        journal.committing();
        database.commitImage(next);
        journal.close();

        PackageJournal.recoverAll(database, installRoot, logger);

        assertTrue(Files.exists(created), "a committed installation must not be reverted");
        assertEquals(next.sha256(), database.currentStatusSha256());
        assertTrue(Files.readString(database.getJournalDir().resolve("after-commit.log"))
                .contains("COMMITTED"));
    }

    @Test
    void installerRecoversIncompleteJournalsOnStartup() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        PackageJournal journal = PackageJournal.begin(installRoot, database, "startup-recovery",
                PackageJournal.OperationType.INSTALL, database.currentImage(), image(database));

        Path created = installRoot.resolve("manager/library/packages/demo/1.0.0/demo.jar");
        Files.createDirectories(created.getParent());
        Files.writeString(created, "new", StandardCharsets.UTF_8);
        journal.created(created);
        journal.close();

        new PackageInstaller(database, installRoot, logger).recoverIncompleteOperations();

        assertFalse(Files.exists(created));
        assertEquals(database.imageOf(List.of()).sha256(), database.currentStatusSha256());
    }

    @Test
    void recoveryAfterCommittedMarkerOnlyCleansUp() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        LocalPackageDatabase.StatusImage next = image(database);
        PackageJournal journal = PackageJournal.begin(installRoot, database, "cleanup",
                PackageJournal.OperationType.INSTALL, database.currentImage(), next);

        Path created = installRoot.resolve("manager/library/packages/demo/1.0.0/demo.jar");
        Files.createDirectories(created.getParent());
        Files.writeString(created, "new", StandardCharsets.UTF_8);
        journal.created(created);
        journal.committing();
        database.commitImage(next);
        journal.committed();
        journal.close();

        PackageJournal.recoverAll(database, installRoot, logger);

        assertTrue(Files.exists(created));
        assertEquals(next.sha256(), database.currentStatusSha256());
        assertFalse(Files.exists(database.getBackupDir().resolve("cleanup")));
    }

    @Test
    void committedMarkerWithoutTheNewStatusStillRollsBack() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        PackageJournal journal = PackageJournal.begin(installRoot, database, "stale-marker",
                PackageJournal.OperationType.INSTALL, database.currentImage(), image(database));

        Path created = installRoot.resolve("manager/library/packages/demo/1.0.0/demo.jar");
        Files.createDirectories(created.getParent());
        Files.writeString(created, "new", StandardCharsets.UTF_8);
        journal.created(created);
        journal.committed();
        journal.close();

        PackageJournal.recoverAll(database, installRoot, logger);

        assertFalse(Files.exists(created),
                "the COMMITTED marker must not override an unpublished status image");
        assertEquals(database.imageOf(List.of()).sha256(), database.currentStatusSha256());
    }

    @Test
    void recoveryToleratesACreatedFileWhoseParentWasNeverMade() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        PackageJournal journal = PackageJournal.begin(installRoot, database, "missing-parent",
                PackageJournal.OperationType.INSTALL, database.currentImage(), image(database));

        Path created = installRoot.resolve("manager/library/packages/demo/1.0.0/demo.jar");
        // The journal record is durable before the move. Simulate a crash before
        // IoSupport.move() created the destination parent directories.
        journal.created(created);
        journal.close();

        PackageJournal.recoverAll(database, installRoot, logger);

        assertFalse(Files.exists(created));
    }

    @Test
    void recoveryBeforeTheBackupMoveKeepsTheOriginalFile() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        PackageJournal journal = PackageJournal.begin(installRoot, database, "replace-window",
                PackageJournal.OperationType.INSTALL, database.currentImage(), image(database));

        Path original = installRoot.resolve("manager/config/shared.yml");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Path backup = database.getBackupDir().resolve("replace-window/manager/config/shared.yml");
        // The journal record is durable before the backup move. Simulate a
        // crash in that window: the original must not be deleted.
        journal.replaced(original, backup);
        journal.close();

        PackageJournal.recoverAll(database, installRoot, logger);

        assertEquals("old", Files.readString(original));
    }

    @Test
    void unknownJournalRecordFailsRecoveryExplicitly() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        Path journal = database.getJournalDir().resolve("unknown.log");
        Files.writeString(journal,
                "V1\top\tINSTALL\told\tsha\t\t\nBOGUS\tvalue\n",
                StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> PackageJournal.recoverAll(database, installRoot, logger));
    }

    @Test
    void traversalInTheJournalOperationIdFailsRecoveryExplicitly() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        Path journal = database.getJournalDir().resolve("traversal.log");
        Files.writeString(journal,
                "V1\t../../escape\tINSTALL\told\tsha\t\t\n",
                StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> PackageJournal.recoverAll(database, installRoot, logger));
    }

    @Test
    void recordWithMissingFieldsFailsRecoveryExplicitly() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        Path journal = database.getJournalDir().resolve("missing-field.log");
        Files.writeString(journal,
                "V1\top\tINSTALL\told\tsha\t\t\nREPLACE\tZGVtby5qYXI=\n",
                StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> PackageJournal.recoverAll(database, installRoot, logger));
    }

    @Test
    void malformedJournalFailsRecoveryExplicitly() throws Exception {
        Path installRoot = tempDir.resolve("install");
        LocalPackageDatabase database = database(installRoot);
        Path journal = database.getJournalDir().resolve("broken.log");
        Files.writeString(journal, "not-a-journal\n", StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> PackageJournal.recoverAll(database, installRoot, logger));
    }

    private LocalPackageDatabase database(Path installRoot) throws Exception {
        LocalPackageDatabase database = new LocalPackageDatabase(installRoot.resolve("manager"));
        database.initialize();
        return database;
    }

    private static LocalPackageDatabase.StatusImage image(LocalPackageDatabase database) {
        String path = "manager/library/packages/demo/1.0.0/demo.jar";
        PackageEntry entry = new PackageEntry("demo", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), List.of(), List.of(), "none", "any", "", "none", "", 1,
                Map.of(path, new FileEntry(path, 1, "", "demo")),
                "", "", Map.of());
        return database.imageOf(List.of(entry));
    }
}
