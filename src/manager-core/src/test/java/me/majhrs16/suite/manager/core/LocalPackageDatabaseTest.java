package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LocalPackageDatabaseTest {

    @TempDir
    Path tempDir;

    private PluginLogger createLogger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };
    }

    @Test
    void initialize_createsDirectories() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        assertTrue(tempDir.resolve("library/status").getParent().toFile().exists());
        assertTrue(tempDir.resolve("library/packages").toFile().exists());
        assertTrue(tempDir.resolve("library/dependencies").toFile().exists());
        assertTrue(tempDir.resolve("library/tmp").toFile().exists());
        assertTrue(tempDir.resolve("library/lists").toFile().exists());
    }

    @Test
    void registerAndGetPackage() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        PackageEntry entry = new PackageEntry(
                "test", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none",
                "sha256", 100,
                Map.of("test.jar", new FileEntry("test.jar", 100, "sha256", "test")),
                "source", "", Map.of()
        );
        
        db.registerPackage(entry);
        
        Optional<PackageEntry> found = db.getPackage("test", "1.0.0", "stable");
        assertTrue(found.isPresent());
        assertEquals("test", found.get().name());
        assertEquals("1.0.0", found.get().version());
    }

    @Test
    void registerDuplicate_throws() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        PackageEntry entry = new PackageEntry(
                "test", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none",
                "sha256", 100,
                Map.of(), "source", "", Map.of()
        );
        
        db.registerPackage(entry);
        assertThrows(IllegalStateException.class, () -> db.registerPackage(entry));
    }

    @Test
    void unregisterPackage() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        PackageEntry entry = new PackageEntry(
                "test", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none",
                "sha256", 100,
                Map.of("test.jar", new FileEntry("test.jar", 100, "sha256", "test")),
                "source", "", Map.of()
        );
        
        db.registerPackage(entry);
        db.unregisterPackage("test", "1.0.0", "stable");
        
        assertFalse(db.getPackage("test", "1.0.0", "stable").isPresent());
    }

    @Test
    void fileOwnership_tracked() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        PackageEntry entry = new PackageEntry(
                "test", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none",
                "sha256", 100,
                Map.of("test.jar", new FileEntry("test.jar", 100, "sha256", "test")),
                "source", "", Map.of()
        );
        
        db.registerPackage(entry);
        
        assertTrue(db.isFileOwned("test.jar"));
        assertEquals(Set.of("test"), db.getFileOwners("test.jar"));
    }

    @Test
    void checkOrphanedFiles() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        PackageEntry entry = new PackageEntry(
                "test", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none",
                "sha256", 100,
                Map.of("test.jar", new FileEntry("test.jar", 100, "sha256", "test")),
                "source", "", Map.of()
        );
        
        db.registerPackage(entry);
        
        Map<String, Set<String>> orphaned = db.checkOrphanedFiles(entry);
        assertEquals(1, orphaned.size());
        assertTrue(orphaned.containsKey("test.jar"));
    }

    @Test
    void getAllPackages() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        PackageEntry entry1 = new PackageEntry(
                "test1", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none", "sha256", 100,
                Map.of(), "source", "", Map.of()
        );
        PackageEntry entry2 = new PackageEntry(
                "test2", "2.0.0", "beta", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none", "sha256", 200,
                Map.of(), "source", "", Map.of()
        );
        
        db.registerPackage(entry1);
        db.registerPackage(entry2);
        
        List<PackageEntry> all = db.getAllPackages();
        assertEquals(2, all.size());
    }

    @Test
    void saveAndLoad_persists() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        
        String validSha256 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        
        PackageEntry entry = new PackageEntry(
                "test", "1.0.0", "stable", PackageType.PACKAGE,
                List.of("dep1"), "none", "common", "", "none",
                validSha256, 100,
                Map.of("test.jar", new FileEntry("test.jar", 100, validSha256, "test")),
                "source", "", Map.of("Installed-Time", "2024-01-01T00:00:00Z")
        );
        
        db.registerPackage(entry);
        db.save();
        
        // Create new database instance and load
        LocalPackageDatabase db2 = new LocalPackageDatabase(tempDir);
        db2.initialize();
        
        Optional<PackageEntry> found = db2.getPackage("test", "1.0.0", "stable");
        assertTrue(found.isPresent());
        assertEquals("test", found.get().name());
        assertEquals("1.0.0", found.get().version());
        assertEquals(List.of("dep1"), found.get().dependencies());
        assertEquals("2024-01-01T00:00:00Z", found.get().extraFields().get("Installed-Time"));
    }
}