package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.textformatter.ChannelConfig;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

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

    @Test
    void loadsConfigWithDefaultsWhenFileMissing() {
        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());
        HostConfig config = result.config();

        assertEquals(true, config.quickLook());
        assertEquals(Language.EN, config.defaultLanguage());
        assertFalse(config.engineParallel());
        assertTrue(config.soundEnabled());
        assertFalse(result.isValid()); // missing file = degraded
    }

    @Test
    void readsConfigValuesFromYaml() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
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
        Files.writeString(dir.resolve("config.yml"), """
            chat:
              claim-mode: no-existe
            """);

        ConfigLoader.LoadResult<HostConfig> result = ConfigLoader.loadConfig(dir, quietLogger());
        assertEquals(HostConfig.ClaimMode.CANCEL_EVENT, result.config().claimMode());
        assertTrue(result.isValid()); // invalid value falls back but config is valid
    }

    @Test
    void parsesEditorExportedDefaultConfig() throws Exception {
        Path cfg = Path.of(ConfigLoaderTest.class
            .getResource("/editor-default/config").toURI());
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