package me.majhrs16.suite.synccommon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncYamlTest {

    @TempDir
    Path dir;

    @Test
    void missingFileYieldsEmptyMap() {
        assertTrue(SyncYaml.readMap(dir.resolve("nope.yml")).isEmpty());
        assertTrue(SyncYaml.readMap(null).isEmpty());
    }

    @Test
    void scalarRootYieldsEmptyMap() throws Exception {
        Path file = dir.resolve("scalar.yml");
        Files.writeString(file, "solo-texto\n");

        assertTrue(SyncYaml.readMap(file).isEmpty());
    }

    @Test
    void invalidYamlYieldsEmptyMap() throws Exception {
        Path file = dir.resolve("broken.yml");
        Files.writeString(file, "enabled: [sin-cerrar\n");

        assertTrue(SyncYaml.readMap(file).isEmpty());
    }

    @Test
    void readsScalarValues() throws Exception {
        Path file = dir.resolve("ok.yml");
        Files.writeString(file, """
            enabled: true
            token: "  abc  "
            port: "9092"
            ratio: 2.5
            channel: 123456789012
            servers: [lobby, " survival "]
            delay: PT5S
            millis: 250
            """);

        Map<String, Object> map = SyncYaml.readMap(file);

        assertTrue(SyncYaml.bool(map, "enabled", false));
        assertEquals("abc", SyncYaml.string(map, "token", ""));
        assertEquals(9092, SyncYaml.integer(map, "port", 0));
        assertEquals(2, SyncYaml.integer(map, "ratio", 0));
        assertEquals(123456789012L, SyncYaml.longValue(map, "channel", 0L));
        assertEquals(2.5, SyncYaml.doubleValue(map, "ratio", 0.0));
        assertEquals(List.of("lobby", "survival"), SyncYaml.stringList(map, "servers"));
        assertEquals(Duration.ofSeconds(5), SyncYaml.duration(map, "delay", Duration.ZERO));
        assertEquals(Duration.ofMillis(250), SyncYaml.duration(map, "millis", Duration.ZERO));
    }

    @Test
    void fallsBackOnMissingOrInvalidValues() {
        Map<String, Object> map = Map.of("port", "no-es-numero", "enabled", "quizas");

        assertEquals(7, SyncYaml.integer(map, "port", 7));
        assertEquals(7L, SyncYaml.longValue(map, "port", 7L));
        assertFalse(SyncYaml.bool(map, "enabled", false));
        assertEquals("def", SyncYaml.string(map, "token", "def"));
        assertFalse(SyncYaml.bool(Map.of(), "enabled", false));
    }
}
