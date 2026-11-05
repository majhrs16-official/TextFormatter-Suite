package me.majhrs16.suite.host;

import me.majhrs16.suite.api.config.ConfigVersion;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.textformatter.ChannelConfig;
import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    private static PluginLogger quietLogger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };
    }

    private Path suiteConfig() throws Exception {
        Path file = dir.resolve("config/suite/config.yml");
        Files.createDirectories(file.getParent());
        return file;
    }

    @Test
    void loadsConfigWithDefaultsWhenFileMissing() {
        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());
        HostConfig config = result.config();

        assertTrue(config.quickLook());
        assertEquals(Language.EN, config.defaultLanguage());
        assertFalse(config.engineParallel());
        assertTrue(config.soundEnabled());
        assertFalse(result.isValid()); // missing file = degraded
    }

    @Test
    void readsConfigValuesFromYaml() throws Exception {
        Files.writeString(suiteConfig(), """
            quick-look: false
            general:
              language: es
            iflow:
              engine:
                parallel: true
            sonido:
              enabled: false
            chat:
              claim-mode: clear-recipients
            config-version: 1.0
            """);

        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());
        HostConfig config = result.config();

        assertFalse(config.quickLook());
        assertEquals(Language.ES, config.defaultLanguage());
        assertTrue(config.engineParallel());
        assertFalse(config.soundEnabled());
        assertEquals(HostConfig.ClaimMode.CLEAR_RECIPIENTS, config.claimMode());
        assertTrue(result.isValid());
    }

    @Test
    void invalidClaimModeFallsBackToCancelEvent() throws Exception {
        Files.writeString(suiteConfig(), """
            chat:
              claim-mode: no-existe
            config-version: 1.0
            """);

        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());
        assertEquals(HostConfig.ClaimMode.CANCEL_EVENT, result.config().claimMode());
        assertTrue(result.isValid()); // invalid value falls back but config is valid
    }

    @Test
    void legacyEngineKindIsIgnored() throws Exception {
        Files.writeString(suiteConfig(), """
            iflow:
              engine:
                kind: firewall
                parallel: true
            config-version: 1.0
            """);

        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());

        assertTrue(result.isValid());
        assertTrue(result.config().engineParallel());
    }

    @Test
    void rejectsConfigWithoutConfigVersion() throws Exception {
        Files.writeString(suiteConfig(), """
            quick-look: false
            """);

        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());

        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.contains(ConfigVersion.MISSING_MESSAGE)));
    }

    @Test
    void rejectsUnsupportedConfigVersion() throws Exception {
        Files.writeString(suiteConfig(), """
            config-version: 2.0
            """);

        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());

        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.contains("config-version '2.0'")));
    }

    @Test
    void parsesEditorExportedDefaultConfig() throws Exception {
        Path cfg = Path.of(ConfigLoaderTest.class
            .getResource("/editor-default").toURI());
        ConfigLoader.LoadResult<HostConfig> configResult = ConfigLoader.loadConfig(cfg, quietLogger());
        ChannelConfig.LoadResult<ChannelRegistry> channelsResult = ChannelConfig.load(cfg);
        HostConfig config = configResult.config();
        ChannelRegistry registry = channelsResult.config();

        assertTrue(config.quickLook());
        assertEquals(Language.EN, config.defaultLanguage());
        assertFalse(config.engineParallel());
        assertTrue(config.soundEnabled());
        assertEquals(HostConfig.ClaimMode.CANCEL_EVENT, config.claimMode());

        assertEquals(4, registry.all().size());
        Channel global = registry.get("chat.global").orElseThrow();
        assertEquals("cht.chat.global", global.permission());
        assertEquals("cht.chat.global.send", global.sendPolicy());
        assertEquals(2, global.messages().formats().length);
        assertEquals(1, global.sounds().size());
        assertEquals("ping-message.mp3", global.sounds().get(0).name());

        Channel staff = registry.get("staff.alert").orElseThrow();
        assertFalse(staff.showSender());
        assertEquals(Language.ES, registry.get("vip.chat").orElseThrow().langSource());
    }
}