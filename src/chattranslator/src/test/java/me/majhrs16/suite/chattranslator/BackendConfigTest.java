package me.majhrs16.suite.chattranslator;

import me.majhrs16.suite.api.spi.TranslatorManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendConfigTest {

    @TempDir
    Path dir;

    private Path canonicalTranslators() throws Exception {
        Path translators = dir.resolve("config/chattranslator/translators");
        Files.createDirectories(translators);
        return translators;
    }

    @Test
    void emptyDirectoryYieldsFallbackOnlyManager() {
        TranslatorManager manager = BackendConfig.load(dir);

        assertEquals("none", manager.active().name());
        assertFalse(manager.active().isAvailable());
    }

    @Test
    void loadsActiveGoogleAndSkipsInactiveLibre() throws Exception {
        Path translators = canonicalTranslators();
        Files.writeString(translators.resolve("google.yml"), """
            provider: google
            active: true
            pool:
              max-concurrent: 6
            config-version: 1.0
            """);
        Files.writeString(translators.resolve("libre.yml"), """
            provider: libre
            active: false
            base-url: https://libretranslate.example
            config-version: 1.0
            """);

        TranslatorManager manager = BackendConfig.load(dir);

        assertEquals(1, manager.providers().size());
        assertEquals("google", manager.active().name());
    }

    @Test
    void libreRequiresBaseUrlWhenActive() throws Exception {
        Path translators = canonicalTranslators();
        Files.writeString(translators.resolve("libre.yml"), """
            provider: libre
            active: true
            config-version: 1.0
            """);

        TranslatorManager manager = BackendConfig.load(dir);

        assertTrue(manager.providers().isEmpty(), "libre sin base-url no debe registrarse");
    }

    @Test
    void malformedYamlSkipsFileWithoutKillingTheRest() throws Exception {
        Path translators = canonicalTranslators();
        Files.writeString(translators.resolve("broken.yml"), "provider: [google");
        Files.writeString(translators.resolve("google.yml"), """
            provider: google
            active: true
            config-version: 1.0
            """);

        TranslatorManager manager = BackendConfig.load(dir);

        assertEquals(1, manager.providers().size());
        assertEquals("google", manager.active().name());
    }

    @Test
    void googleRegistersBeforeLibreRegardlessOfDirectoryOrder() throws Exception {
        Path translators = canonicalTranslators();
        Files.writeString(translators.resolve("libre.yml"), """
            provider: libre
            active: true
            base-url: https://libretranslate.example
            config-version: 1.0
            """);
        Files.writeString(translators.resolve("google.yml"), """
            provider: google
            active: true
            config-version: 1.0
            """);

        TranslatorManager manager = BackendConfig.load(dir);

        assertEquals(2, manager.providers().size());
        assertEquals("google", manager.providers().get(0).name());
        assertEquals("libre", manager.providers().get(1).name());
    }

    @Test
    void resolvesLegacyTranslatorsLocationDuringMigration() throws Exception {
        Path legacy = dir.resolve("translators");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("google.yml"), """
            provider: google
            active: true
            config-version: 1.0
            """);

        assertEquals(legacy, BackendConfig.resolveTranslatorsDir(dir));
        assertEquals("google", BackendConfig.load(dir).active().name());
    }

    @Test
    void canonicalTranslatorsWinOverLegacy() throws Exception {
        Path canonical = canonicalTranslators();
        Files.writeString(canonical.resolve("google.yml"),
            "provider: google\nactive: true\nconfig-version: 1.0\n");
        Path legacy = dir.resolve("translators");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("libre.yml"),
            "provider: libre\nactive: true\nbase-url: https://x\nconfig-version: 1.0\n");

        TranslatorManager manager = BackendConfig.load(dir);

        assertEquals(1, manager.providers().size());
        assertEquals("google", manager.active().name());
    }

    @Test
    void loadsChatTranslatorConfigFromCanonicalLocation() throws Exception {
        Path configDir = dir.resolve("config/chattranslator");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yml"), """
            default-backend: libre
            context-override-enabled: false
            cache-max-size: 42
            config-version: 1.0
            """);

        ChatTranslatorConfig.Config config = ChatTranslatorConfig.load(dir);

        assertEquals("libre", config.defaultBackend());
        assertFalse(config.contextOverrideEnabled());
        assertEquals(42, config.cacheMaxSize());
    }

    @Test
    void legacyChatTranslatorConfigStillLoads() throws Exception {
        Files.createDirectories(dir.resolve("chattranslator"));
        Files.writeString(dir.resolve("chattranslator/config.yml"),
            "default-backend: google\nconfig-version: 1.0\n");

        ChatTranslatorConfig.Config config = ChatTranslatorConfig.load(dir);

        assertEquals("google", config.defaultBackend());
    }

    @Test
    void missingChatTranslatorConfigUsesDefaults() {
        ChatTranslatorConfig.Config config = ChatTranslatorConfig.load(dir);

        assertEquals("google", config.defaultBackend());
        assertTrue(config.contextOverrideEnabled());
    }
}
