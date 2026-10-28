package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.manager.spi.FileEntry;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.core.PackageInstaller.InstallException;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackageInstallerTest {

    @TempDir
    Path tempDir;

    private PluginLogger createLogger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { System.out.println("[INFO] " + String.format(m, a)); }
            @Override public void warn(String m, Object... a) { System.out.println("[WARN] " + String.format(m, a)); }
            @Override public void error(String m, Object... a) { System.err.println("[ERROR] " + String.format(m, a)); }
            @Override public void error(String m, Throwable t) { System.err.println("[ERROR] " + m); t.printStackTrace(); }
            @Override public void debug(String m, Object... a) {}
        };
    }

    @Test
    void install_validPackage() throws Exception {
        Path zipPath = tempDir.resolve("test-package.zip");
        createValidZip(zipPath, "test", "1.0.0", "stable", "test.jar", "content".getBytes());
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        try {
            List<PackageEntry> installed = installer.install(zipPath, false);
            
            assertEquals(1, installed.size());
            assertEquals("test", installed.get(0).name());
            assertEquals("1.0.0", installed.get(0).version());
            assertTrue(Files.exists(installRoot.resolve("test.jar")));
        } catch (InstallException e) {
            System.err.println("InstallException: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    void install_invalidZipSha256_throws() throws Exception {
        Path zipPath = tempDir.resolve("bad-package.zip");
        
        // Create a valid ZIP first, then corrupt its SHA256 in manifest
        // Use a valid-looking but incorrect SHA256 (64 hex chars)
        byte[] fileContent = "content".getBytes();
        String correctFileSha256 = computeSha256(fileContent);
        String wrongZipSha256 = "0".repeat(64);
        createValidZipWithCorruptedZipSha256(zipPath, "bad", "1.0.0", "stable", "bad.jar", fileContent, correctFileSha256, wrongZipSha256);
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        
        try {
            installer.install(zipPath, false);
            fail("Expected InstallException for invalid ZIP SHA256");
        } catch (InstallException ex) {
            System.err.println("Got exception: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("ZIP SHA256 mismatch") || ex.getMessage().contains("ZIP SHA256"), 
                "Exception message: " + ex.getMessage());
        }
    }

    @Test
    void install_invalidFileSha256_throws() throws Exception {
        Path zipPath = tempDir.resolve("bad-file-sha.zip");
        
        // Create ZIP with correct ZIP SHA256 but wrong file SHA256 in manifest
        // Use a valid-looking but incorrect SHA256 (64 hex chars)
        byte[] fileContent = "content".getBytes();
        String correctFileSha256 = computeSha256(fileContent);
        String wrongFileSha256 = "0".repeat(64); // Valid format but wrong value
        createValidZipWithCorruptedFileSha256(zipPath, "bad-file", "1.0.0", "stable", "bad.jar", fileContent, correctFileSha256, wrongFileSha256);
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        
        try {
            installer.install(zipPath, false);
            fail("Expected InstallException for invalid file SHA256");
        } catch (InstallException ex) {
            System.err.println("Got exception: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("hash mismatch") || ex.getMessage().contains("SHA256 mismatch"), 
                "Exception message: " + ex.getMessage());
        }
    }

    @Test
    void install_duplicateFileInManifest_throws() throws Exception {
        Path zipPath = tempDir.resolve("dup-file.zip");
        
        // Create ZIP with duplicate file entry in manifest
        byte[] fileContent = "content".getBytes();
        String fileSha256 = computeSha256(fileContent);
        createZipWithDuplicateFile(zipPath, "dup", "1.0.0", "stable", "dup.jar", fileContent, fileSha256);
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        
        InstallException ex = assertThrows(InstallException.class, () -> installer.install(zipPath, false));
        assertTrue(ex.getMessage().contains("Duplicate package name") || ex.getMessage().contains("duplicate") || ex.getMessage().contains("Duplicate"));
    }

    @Test
    void install_duplicatePackageName_throws() throws Exception {
        Path zipPath = tempDir.resolve("dup-pkg.zip");
        
        // Create ZIP with two packages having the same name
        byte[] fileContent = "content".getBytes();
        String fileSha256 = computeSha256(fileContent);
        createZipWithDuplicatePackage(zipPath, "dup", "1.0.0", "stable", "dup.jar", fileContent, fileSha256);
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        
        InstallException ex = assertThrows(InstallException.class, () -> installer.install(zipPath, false));
        assertTrue(ex.getMessage().contains("Duplicate package name"));
    }

    @Test
    void remove_package() throws Exception {
        Path zipPath = tempDir.resolve("test-package.zip");
        createValidZip(zipPath, "test", "1.0.0", "stable", "test.jar", "content".getBytes());
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        installer.install(zipPath, false);
        
        assertTrue(Files.exists(installRoot.resolve("test.jar")));
        assertTrue(db.isInstalled("test"));
        
        installer.remove("test", "1.0.0", "stable", false);
        
        assertFalse(Files.exists(installRoot.resolve("test.jar")));
        assertFalse(db.isInstalled("test"));
    }

    @Test
    void remove_withDependencies_throws() throws Exception {
        Path zipPath1 = tempDir.resolve("dep.zip");
        createValidZip(zipPath1, "dependency", "1.0.0", "stable", "dep.jar", "dep".getBytes());
        
        Path zipPath2 = tempDir.resolve("main.zip");
        // Create main package that depends on dependency
        byte[] mainContent = "main".getBytes();
        String mainFileSha256 = computeSha256(mainContent);
        createValidZipWithDependency(zipPath2, "main", "1.0.0", "stable", "main.jar", mainContent, mainFileSha256, "dependency@*");
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        installer.install(zipPath1, false);
        installer.install(zipPath2, false);
        
        // Try to remove dependency - should fail
        InstallException ex = assertThrows(InstallException.class, () -> 
            installer.remove("dependency", "1.0.0", "stable", false));
        assertTrue(ex.getMessage().contains("required by") || ex.getMessage().contains("Cannot remove"));
    }

    @Test
    void install_forceOverwritesExistingFile() throws Exception {
        // Install first package
        Path zipPath1 = tempDir.resolve("pkg1.zip");
        createValidZip(zipPath1, "pkg1", "1.0.0", "stable", "shared.jar", "v1".getBytes());
        
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);
        
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());
        installer.install(zipPath1, false);
        
        // Verify pkg1 installed
        assertEquals(1, db.getAllPackages().size());
        
        // Try to install second package with same file (should fail without force)
        Path zipPath2 = tempDir.resolve("pkg2.zip");
        createValidZip(zipPath2, "pkg2", "1.0.0", "stable", "shared.jar", "v2".getBytes());
        
        InstallException ex = assertThrows(InstallException.class, () -> 
            installer.install(zipPath2, false));
        assertTrue(ex.getMessage().contains("File conflict"));
        
        // Verify pkg1 still installed after failed attempt
        assertEquals(1, db.getAllPackages().size());
        
        // With force, should succeed
        installer.install(zipPath2, true);
        
        // Both packages should now be installed (pkg2 overwrote pkg1's file)
        assertEquals(2, db.getAllPackages().size());
    }

    @Test
    void install_packageExcludeConflict_throws() throws Exception {
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);

        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());

        Path firewallZip = tempDir.resolve("iflow-firewall.zip");
        createZipWithMetadata(firewallZip, "iflow-firewall", "2.1.0", "stable",
                "firewall.jar", "firewall".getBytes(), "", "iflow-vm", "iflow");
        installer.install(firewallZip, false);

        Path vmZip = tempDir.resolve("iflow-vm.zip");
        createZipWithMetadata(vmZip, "iflow-vm", "2.1.0", "stable",
                "vm.jar", "vm".getBytes(), "", "", "iflow");

        InstallException ex = assertThrows(InstallException.class, () -> installer.install(vmZip, false));
        assertTrue(ex.getMessage().contains("Package conflict"));
        assertTrue(db.isInstalled("iflow-firewall"));
        assertFalse(db.isInstalled("iflow-vm"));
    }

    @Test
    void install_forceAllowsExcludeConflict() throws Exception {
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);

        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());

        Path firewallZip = tempDir.resolve("iflow-firewall-force.zip");
        createZipWithMetadata(firewallZip, "iflow-firewall", "2.1.0", "stable",
                "firewall-force.jar", "firewall".getBytes(), "", "iflow-vm", "iflow");
        installer.install(firewallZip, false);

        Path vmZip = tempDir.resolve("iflow-vm-force.zip");
        createZipWithMetadata(vmZip, "iflow-vm", "2.1.0", "stable",
                "vm-force.jar", "vm".getBytes(), "", "", "iflow");

        // force is a deliberate operator decision: both exclusive packages coexist.
        installer.install(vmZip, true);

        assertTrue(db.isInstalled("iflow-firewall"));
        assertTrue(db.isInstalled("iflow-vm"));
    }

    @Test
    void install_dependencySatisfiedByProvide() throws Exception {
        Path installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot);

        LocalPackageDatabase db = new LocalPackageDatabase(tempDir.resolve("manager"));
        db.initialize();
        PackageInstaller installer = new PackageInstaller(db, installRoot, createLogger());

        Path providerZip = tempDir.resolve("provider.zip");
        createZipWithMetadata(providerZip, "iflow-vm", "2.1.0", "stable",
                "provider.jar", "provider".getBytes(), "", "", "iflow");
        installer.install(providerZip, false);

        Path consumerZip = tempDir.resolve("consumer.zip");
        createZipWithMetadata(consumerZip, "consumer", "1.0.0", "stable",
                "consumer.jar", "consumer".getBytes(), "iflow@>=2.0.0", "", "");
        installer.install(consumerZip, false);

        assertTrue(db.isInstalled("consumer"));
    }

    private void createZipWithMetadata(Path zipPath, String name, String version, String channel,
                                       String fileName, byte[] fileContent,
                                       String dependencies, String exclude, String provide) throws Exception {
        String fileSha256 = computeSha256(fileContent);
        StringBuilder manifest = new StringBuilder();
        manifest.append("Name: ").append(name).append("\n")
                .append("Version: ").append(version).append("\n")
                .append("Channel: ").append(channel).append("\n")
                .append("Type: PACKAGE\n")
                .append("Dependencies: ").append(dependencies).append("\n");
        if (!exclude.isBlank()) {
            manifest.append("Exclude: ").append(exclude).append("\n");
        }
        if (!provide.isBlank()) {
            manifest.append("Provide: ").append(provide).append("\n");
        }
        manifest.append("Arch: none\n")
                .append("Platform: common\n")
                .append("URL: \n")
                .append("OS: none\n")
                .append("SHA256: \n")
                .append("Size: 0\n")
                .append("Files: \n")
                .append("  ").append(fileName).append("\t")
                .append(fileContent.length).append("\t")
                .append(fileSha256).append("\t")
                .append(name);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry manifestEntry = new ZipEntry("METADATA/package.list");
            zos.putNextEntry(manifestEntry);
            zos.write(manifest.toString().getBytes());
            zos.closeEntry();

            ZipEntry fileEntry = new ZipEntry(fileName);
            zos.putNextEntry(fileEntry);
            zos.write(fileContent);
            zos.closeEntry();
        }
    }

    private void createValidZip(Path zipPath, String name, String version, String channel,
                                String fileName, byte[] fileContent) throws Exception {
        String fileSha256 = computeSha256(fileContent);
        createValidZipFull(zipPath, name, version, channel, fileName, fileContent, fileSha256, null);
    }

    private void createValidZipWithZipSha256(Path zipPath, String name, String version, String channel,
                                             String fileName, byte[] fileContent, String zipSha256, String fileSha256) throws Exception {
        createValidZipFull(zipPath, name, version, channel, fileName, fileContent, fileSha256, zipSha256);
    }

    private void createValidZipWithFileSha256(Path zipPath, String name, String version, String channel,
                                              String fileName, byte[] fileContent, String zipSha256, String fileSha256) throws Exception {
        createValidZipFull(zipPath, name, version, channel, fileName, fileContent, fileSha256, zipSha256);
    }

    private void createValidZipWithDependency(Path zipPath, String name, String version, String channel,
                                              String fileName, byte[] fileContent, String fileSha256,
                                              String dependency) throws Exception {
        // Use empty SHA256 and Size: 0 in manifest to skip validation
        String finalManifest = String.format(
            "Name: %s\nVersion: %s\nChannel: %s\nType: PACKAGE\nDependencies: %s\nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: \nSize: 0\nFiles: \n  %s\t%d\t%s\t%s",
            name, version, channel, dependency, fileName, fileContent.length, fileSha256, name
        );
        
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry manifestEntry = new ZipEntry("METADATA/package.list");
            zos.putNextEntry(manifestEntry);
            zos.write(finalManifest.getBytes());
            zos.closeEntry();
            
            ZipEntry fileEntry = new ZipEntry(fileName);
            zos.putNextEntry(fileEntry);
            zos.write(fileContent);
            zos.closeEntry();
        }
    }

    private void createValidZipFull(Path zipPath, String name, String version, String channel,
                                    String fileName, byte[] fileContent, String fileSha256, String zipSha256Override) throws Exception {
        // Use empty SHA256 and Size: 0 in manifest to skip validation
        String finalManifest = String.format(
            "Name: %s\nVersion: %s\nChannel: %s\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: \nSize: 0\nFiles: \n  %s\t%d\t%s\t%s",
            name, version, channel, fileName, fileContent.length, fileSha256, name
        );
        
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry manifestEntry = new ZipEntry("METADATA/package.list");
            zos.putNextEntry(manifestEntry);
            zos.write(finalManifest.getBytes());
            zos.closeEntry();
            
            ZipEntry fileEntry = new ZipEntry(fileName);
            zos.putNextEntry(fileEntry);
            zos.write(fileContent);
            zos.closeEntry();
        }
    }

    private void createZipWithDuplicateFile(Path zipPath, String name, String version, String channel,
                                            String fileName, byte[] fileContent, String fileSha256) throws Exception {
        // Manifest with duplicate file entry (same path twice)
        // Use empty SHA256 and Size: 0 to skip ZIP validation
        String finalManifest = String.format(
            "Name: %s\nVersion: %s\nChannel: %s\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: \nSize: 0\nFiles: \n  %s\t%d\t%s\t%s\n  %s\t%d\t%s\t%s",
            name, version, channel,
            fileName, fileContent.length, fileSha256, name,
            fileName, fileContent.length, fileSha256, name
        );
        
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry manifestEntry = new ZipEntry("METADATA/package.list");
            zos.putNextEntry(manifestEntry);
            zos.write(finalManifest.getBytes());
            zos.closeEntry();
            
            // Write file once (the manifest says it twice)
            ZipEntry fileEntry = new ZipEntry(fileName);
            zos.putNextEntry(fileEntry);
            zos.write(fileContent);
            zos.closeEntry();
        }
    }

    private void createZipWithDuplicatePackage(Path zipPath, String name, String version, String channel,
                                               String fileName, byte[] fileContent, String fileSha256) throws Exception {
        // Manifest with two packages having the same name
        // Use empty SHA256 and Size: 0 to skip ZIP validation
        String finalManifest = String.format(
            "Name: %s\nVersion: %s\nChannel: %s\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: \nSize: 0\nFiles: \n  %s\t%d\t%s\t%s\n\n" +
            "Name: %s\nVersion: %s\nChannel: %s\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: \nSize: 0\nFiles: \n  %s\t%d\t%s\t%s",
            name, version, channel, fileName, fileContent.length, fileSha256, name,
            name, version, channel, fileName, fileContent.length, fileSha256, name
        );
        
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry manifestEntry = new ZipEntry("METADATA/package.list");
            zos.putNextEntry(manifestEntry);
            zos.write(finalManifest.getBytes());
            zos.closeEntry();
            
            ZipEntry fileEntry = new ZipEntry(fileName);
            zos.putNextEntry(fileEntry);
            zos.write(fileContent);
            zos.closeEntry();
        }
    }

    private void createValidZipWithCorruptedZipSha256(Path zipPath, String name, String version, String channel,
                                                    String fileName, byte[] fileContent, String fileSha256, String wrongZipSha256) throws Exception {
        // Create ZIP with wrong ZIP SHA256 in manifest (Size: 0 to skip size validation)
        String finalManifest = String.format(
            "Name: %s\nVersion: %s\nChannel: %s\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: %s\nSize: 0\nFiles: \n  %s\t%d\t%s\t%s",
            name, version, channel, wrongZipSha256, fileName, fileContent.length, fileSha256, name
        );
        
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry manifestEntry = new ZipEntry("METADATA/package.list");
            zos.putNextEntry(manifestEntry);
            zos.write(finalManifest.getBytes());
            zos.closeEntry();
            
            ZipEntry fileEntry = new ZipEntry(fileName);
            zos.putNextEntry(fileEntry);
            zos.write(fileContent);
            zos.closeEntry();
        }
    }

    private void createValidZipWithCorruptedFileSha256(Path zipPath, String name, String version, String channel,
                                                       String fileName, byte[] fileContent, String fileSha256, String wrongFileSha256) throws Exception {
        // Create ZIP with correct ZIP SHA256 (empty to skip) but wrong file SHA256 in manifest
        // Size: 0 to skip size validation
        String finalManifest = String.format(
            "Name: %s\nVersion: %s\nChannel: %s\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: \nSize: 0\nFiles: \n  %s\t%d\t%s\t%s",
            name, version, channel, fileName, fileContent.length, wrongFileSha256, name
        );
        
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry manifestEntry = new ZipEntry("METADATA/package.list");
            zos.putNextEntry(manifestEntry);
            zos.write(finalManifest.getBytes());
            zos.closeEntry();
            
            ZipEntry fileEntry = new ZipEntry(fileName);
            zos.putNextEntry(fileEntry);
            zos.write(fileContent);
            zos.closeEntry();
        }
    }

    private String computeSha256(byte[] content) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String computeSha256(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}