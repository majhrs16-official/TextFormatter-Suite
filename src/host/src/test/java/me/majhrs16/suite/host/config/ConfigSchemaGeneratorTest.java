package me.majhrs16.suite.host.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSchemaGeneratorTest {

    @TempDir
    Path dir;

    @Test
    void generatesSchemaArtifactsForTheWebEditor() throws Exception {
        ConfigSchemaGenerator.main(new String[]{dir.toString()});

        Path pathsJson = dir.resolve("paths.json");
        Path pathsJs = dir.resolve("paths.js");
        Path modelJs = dir.resolve("model.js");

        assertTrue(Files.exists(pathsJson), "paths.json");
        assertTrue(Files.exists(pathsJs), "paths.js");
        assertTrue(Files.exists(modelJs), "model.js");
        assertTrue(Files.size(pathsJson) > 0, "paths.json no debe estar vacío");
        assertTrue(Files.size(pathsJs) > 0, "paths.js no debe estar vacío");
        assertTrue(Files.size(modelJs) > 0, "model.js no debe estar vacío");

        String json = Files.readString(pathsJson);
        assertTrue(json.contains("quick-look"), "paths.json debe incluir claves del ConfigPath");
        assertTrue(json.contains("channels"), "paths.json debe incluir rutas de canales");
        assertFalse(json.isBlank());

        String js = Files.readString(pathsJs);
        assertTrue(js.contains("quick-look"));
        String model = Files.readString(modelJs);
        assertTrue(model.contains("CHANNEL_DEFAULTS"), "model.js expone los defaults de canal");
    }

    @Test
    void generationIsDeterministic() throws Exception {
        Path first = Files.createDirectories(dir.resolve("first"));
        Path second = Files.createDirectories(dir.resolve("second"));

        ConfigSchemaGenerator.main(new String[]{first.toString()});
        ConfigSchemaGenerator.main(new String[]{second.toString()});

        for (String name : new String[]{"paths.json", "paths.js", "model.js"}) {
            assertTrue(Files.readString(first.resolve(name))
                .equals(Files.readString(second.resolve(name))), name + " debe ser determinista");
        }
    }
}
