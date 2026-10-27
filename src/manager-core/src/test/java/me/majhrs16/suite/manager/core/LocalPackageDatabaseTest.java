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
        assertEquals(Set.of("test_1.0.0_stable"), db.getFileOwners("test.jar"));
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

    @Test
    void saveAndLoad_preservesExcludeAndProvide() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();
        db.registerPackage(packageEntry("iflow-vm", "2.1.0", List.of(),
                List.of("iflow-firewall", "iflow-wasm"), List.of("iflow")));

        LocalPackageDatabase reloaded = new LocalPackageDatabase(tempDir);
        reloaded.initialize();

        PackageEntry entry = reloaded.getPackage("iflow-vm", "2.1.0", "stable").orElseThrow();
        assertEquals(List.of("iflow-firewall", "iflow-wasm"), entry.excludes());
        assertEquals(List.of("iflow"), entry.provides());
    }

    @Test
    void findConflicts_isSymmetricAndHonoursProvide() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();

        PackageEntry firewall = packageEntry("iflow-firewall", "2.1.0", List.of(),
                List.of("iflow-vm"), List.of("iflow"));
        PackageEntry vm = packageEntry("iflow-vm", "2.1.0", List.of(),
                List.of(), List.of("iflow"));

        db.registerPackage(firewall);

        assertEquals(List.of(firewall), db.findConflicts(vm));
        assertTrue(LocalPackageDatabase.conflicts(firewall, vm));
        assertFalse(LocalPackageDatabase.conflicts(vm, vm));
    }

    @Test
    void dependencyCanBeSatisfiedByProvidedName() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();

        db.registerPackage(packageEntry("iflow-vm", "2.1.0", List.of(),
                List.of(), List.of("iflow")));

        assertTrue(db.isDependencySatisfied("iflow", ">=2.0.0"));
        assertTrue(db.findInstalledProvider("iflow").isPresent());
        assertFalse(db.isDependencySatisfied("iflow", ">=3.0.0"));
    }

    @Test
    void reverseDependencies_honourProvideAndOtherProviders() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        db.initialize();

        PackageEntry vm = packageEntry("iflow-vm", "2.1.0", List.of(),
                List.of(), List.of("iflow"));
        PackageEntry firewall = packageEntry("iflow-firewall", "2.1.0", List.of(),
                List.of(), List.of("iflow"));
        PackageEntry consumer = packageEntry("consumer", "1.0.0", List.of("iflow"),
                List.of(), List.of());

        db.registerPackage(firewall);
        db.registerPackage(consumer);
        assertEquals(List.of(consumer), db.getReverseDependencies("iflow-firewall"));

        db.registerPackage(vm);
        assertTrue(db.getReverseDependencies("iflow-firewall").isEmpty());
    }

    private PackageEntry packageEntry(String name, String version, List<String> dependencies,
                                      List<String> excludes, List<String> provides) {
        return new PackageEntry(
                name, version, "stable", PackageType.PACKAGE,
                dependencies, excludes, provides, "none", "common", "", "none",
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                100, Map.of(), "source", "", Map.of()
        );
    }
}