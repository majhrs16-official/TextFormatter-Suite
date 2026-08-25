package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.TranslatorManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorsConfigTest {

    @TempDir
    Path dir;

    @Test
    void emptyDirectoryYieldsFallbackOnlyManager() {
        TranslatorManager manager = TranslatorsConfig.load(dir);

        assertEquals("none", manager.active().name());
        assertFalse(manager.active().isAvailable());
    }

    @Test
    void loadsActiveGoogleAndSkipsInactiveLibre() throws Exception {
        Files.createDirectories(dir.resolve("translators"));
        Files.writeString(dir.resolve("translators/google.yml"), """
            provider: google
            active: true
            pool:
              max-concurrent: 6
            """);
        Files.writeString(dir.resolve("translators/libre.yml"), """
            provider: libre
            active: false
            base-url: https://libretranslate.example
            """);

        TranslatorManager manager = TranslatorsConfig.load(dir);

        assertEquals(1, manager.providers().size());
        assertEquals("google", manager.active().name());
    }

    @Test
    void libreRequiresBaseUrlWhenActive() throws Exception {
        Files.createDirectories(dir.resolve("translators"));
        Files.writeString(dir.resolve("translators/libre.yml"), """
            provider: libre
            active: true
            """);

        TranslatorManager manager = TranslatorsConfig.load(dir);

        assertTrue(manager.providers().isEmpty(), "libre without base-url must not register");
    }

    @Test
    void malformedYamlSkipsFileWithoutKillingTheRest() throws Exception {
        Files.createDirectories(dir.resolve("translators"));
        Files.writeString(dir.resolve("translators/broken.yml"), "provider: [google");
        Files.writeString(dir.resolve("translators/google.yml"), """
            provider: google
            active: true
            """);

        TranslatorManager manager = TranslatorsConfig.load(dir);

        assertEquals(1, manager.providers().size());
        assertEquals("google", manager.active().name());
    }

    @Test
    void googleRegistersBeforeLibreRegardlessOfDirectoryOrder() throws Exception {
        Files.createDirectories(dir.resolve("translators"));
        Files.writeString(dir.resolve("translators/libre.yml"), """
            provider: libre
            active: true
            base-url: https://libretranslate.example
            """);
        Files.writeString(dir.resolve("translators/google.yml"), """
            provider: google
            active: true
            """);

        TranslatorManager manager = TranslatorsConfig.load(dir);

        assertEquals(2, manager.providers().size());
        assertEquals("google", manager.providers().get(0).name());
        assertEquals("libre", manager.providers().get(1).name());
    }
}
