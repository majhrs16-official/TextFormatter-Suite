package me.majhrs16.suite.manager.spi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManifestParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parseRemoteIndex_validEntry() throws Exception {
        String content = "iflow\thttps://example.com/iflow-1.0.0.zip\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        List<ManifestParser.RemoteIndexEntry> entries = ManifestParser.parseRemoteIndex(content);
        
        assertEquals(1, entries.size());
        assertEquals("iflow", entries.get(0).name());
        assertEquals("https://example.com/iflow-1.0.0.zip", entries.get(0).url());
        assertEquals(48213, entries.get(0).size());
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", entries.get(0).sha256());
    }

    @Test
    void parseRemoteIndex_multipleEntries() throws Exception {
        String content = "iflow\thttps://example.com/iflow-1.0.0.zip\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\n" +
                         "textformatter\thttps://example.com/textformatter-2.0.0.zip\t51200\t1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        List<ManifestParser.RemoteIndexEntry> entries = ManifestParser.parseRemoteIndex(content);
        
        assertEquals(2, entries.size());
    }

    @Test
    void parseRemoteIndex_skipsComments() throws Exception {
        String content = "# This is a comment\niflow\thttps://example.com/iflow-1.0.0.zip\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        List<ManifestParser.RemoteIndexEntry> entries = ManifestParser.parseRemoteIndex(content);
        
        assertEquals(1, entries.size());
    }

    @Test
    void parseRemoteIndex_invalidSha256_throws() {
        String content = "iflow\thttps://example.com/iflow-1.0.0.zip\t48213\tinvalid";
        assertThrows(ManifestParser.ParseException.class, () -> ManifestParser.parseRemoteIndex(content));
    }

    @Test
    void parseRemoteIndex_wrongFieldCount_throws() {
        String content = "iflow\thttps://example.com/iflow-1.0.0.zip\t48213";
        assertThrows(ManifestParser.ParseException.class, () -> ManifestParser.parseRemoteIndex(content));
    }

    @Test
    void parseRemoteIndex_negativeSize_throws() {
        String content = "iflow\thttps://example.com/iflow-1.0.0.zip\t-1\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        assertThrows(ManifestParser.ParseException.class, () -> ManifestParser.parseRemoteIndex(content));
    }

    @Test
    void parseManifest_singlePackage() throws Exception {
        String content = "Name: iflow\nVersion: 1.0.0\nChannel: stable\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\nSize: 48213\nFiles: \n  iflow.jar\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\tiflow";
        
        List<PackageEntry> entries = ManifestParser.parseManifest(content);
        assertEquals(1, entries.size());
        assertEquals("iflow", entries.get(0).name());
        assertEquals("1.0.0", entries.get(0).version());
        assertEquals("stable", entries.get(0).channel());
    }

    @Test
    void parseManifest_multiplePackages() throws Exception {
        String content = "Name: iflow\nVersion: 1.0.0\nChannel: stable\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\nSize: 48213\nFiles: \n  iflow.jar\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\tiflow\n\n" +
                       "Name: textformatter\nVersion: 2.0.0\nChannel: stable\nType: PACKAGE\nDependencies: iflow\nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: 1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef\nSize: 51200\nFiles: \n  textformatter.jar\t51200\t1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef\ttextformatter";
        
        List<PackageEntry> entries = ManifestParser.parseManifest(content);
        assertEquals(2, entries.size());
    }

    @Test
    void parseManifest_duplicateFilePath_throws() {
        String content = "Name: iflow\nVersion: 1.0.0\nChannel: stable\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\nSize: 48213\nFiles: \n  iflow.jar\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\tiflow\n  iflow.jar\t48213\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\tiflow";
        
        assertThrows(ManifestParser.ParseException.class, () -> ManifestParser.parseManifest(content));
    }

    @Test
    void parseManifest_negativeFileSize_throws() {
        String content = "Name: iflow\nVersion: 1.0.0\nChannel: stable\nType: PACKAGE\nDependencies: \nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\nSize: 48213\nFiles: \n  iflow.jar\t-1\tabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\tiflow";
        
        assertThrows(ManifestParser.ParseException.class, () -> ManifestParser.parseManifest(content));
    }

    @Test
    void serializeAndParse_roundTrip() throws Exception {
        PackageEntry entry = new PackageEntry(
                "iflow", "1.0.0", "stable", PackageType.PACKAGE,
                List.of(), "none", "common", "", "none",
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                48213,
                java.util.Map.of("iflow.jar", new FileEntry("iflow.jar", 48213, 
                        "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", "iflow")),
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                "", java.util.Map.of()
        );
        
        String serialized = ManifestParser.serialize(entry);
        System.out.println("Serialized:\n" + serialized);
        List<PackageEntry> parsed = ManifestParser.parseManifest(serialized);
        
        assertEquals(1, parsed.size());
        assertEquals(entry.name(), parsed.get(0).name());
        assertEquals(entry.version(), parsed.get(0).version());
        assertEquals(entry.channel(), parsed.get(0).channel());
        assertEquals(entry.files().size(), parsed.get(0).files().size());
    }

    @Test
    void dependencySpec_parseWithoutConstraint() {
        ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse("textformatter");
        assertEquals("textformatter", spec.name());
        assertEquals("*", spec.constraint());
    }

    @Test
    void dependencySpec_parseWithAtConstraint() {
        ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse("textformatter@>=1.0.0");
        assertEquals("textformatter", spec.name());
        assertEquals(">=1.0.0", spec.constraint());
    }

    @Test
    void dependencySpec_parseWithSpaceConstraint() {
        ManifestParser.DependencySpec spec = ManifestParser.DependencySpec.parse("textformatter ^1.0.0");
        assertEquals("textformatter", spec.name());
        assertEquals("^1.0.0", spec.constraint());
    }

    @Test
    void dependencySpec_toStringWithoutConstraint() {
        ManifestParser.DependencySpec spec = new ManifestParser.DependencySpec("textformatter", "*");
        assertEquals("textformatter", spec.toString());
    }

    @Test
    void dependencySpec_toStringWithConstraint() {
        ManifestParser.DependencySpec spec = new ManifestParser.DependencySpec("textformatter", ">=1.0.0");
        assertEquals("textformatter@>=1.0.0", spec.toString());
    }

    @Test
    void parseManifest_dependenciesWithConstraints() throws Exception {
        String content = "Name: textformatter\nVersion: 2.0.0\nChannel: stable\nType: PACKAGE\nDependencies: iflow@>=1.0.0, gtranslate ^1.0.0\nArch: none\nPlatform: common\nURL: \nOS: none\nSHA256: 1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef\nSize: 51200\nFiles: \n  textformatter.jar\t51200\t1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef\ttextformatter";
        
        List<PackageEntry> entries = ManifestParser.parseManifest(content);
        assertEquals(1, entries.size());
        assertEquals(2, entries.get(0).dependencies().size());
        assertTrue(entries.get(0).dependencies().contains("iflow@>=1.0.0"));
        assertTrue(entries.get(0).dependencies().contains("gtranslate@^1.0.0"));
    }
}