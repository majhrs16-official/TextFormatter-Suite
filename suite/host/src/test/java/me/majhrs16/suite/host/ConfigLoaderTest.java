package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
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

    @Test
    void loadsConfigWithDefaultsWhenFileMissing() {
        HostConfig config = ConfigLoader.loadConfig(dir);

        assertEquals(true, config.quickLook());
        assertEquals(Language.EN, config.defaultLanguage());
        assertFalse(config.engineParallel());
        assertTrue(config.soundEnabled());
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

        HostConfig config = ConfigLoader.loadConfig(dir);

        assertFalse(config.quickLook());
        assertEquals(Language.ES, config.defaultLanguage());
        assertTrue(config.engineParallel());
        assertFalse(config.soundEnabled());
        assertEquals(HostConfig.ClaimMode.CLEAR_RECIPIENTS, config.claimMode());
    }

    @Test
    void invalidClaimModeFallsBackToCancelEvent() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
            chat:
              claim-mode: no-existe
            """);

        assertEquals(HostConfig.ClaimMode.CANCEL_EVENT, ConfigLoader.loadConfig(dir).claimMode());
    }

    @Test
    void loadsChannelsFromDirectory() throws Exception {
        Files.createDirectories(dir.resolve("channels"));
        Files.writeString(dir.resolve("channels/chat.yml"), """
            name: chat
            permission: cht.chat
            send-permission: cht.chat.send
            lang-target: es
            rate-limit-per-second: 3
            messages:
              - '<gray><tr>%content%</tr></gray>'
            sounds:
              - name: BLOCK_NOTE_BLOCK_PLING
                volume: 0.5
                pitch: 1.2
            """);
        Files.writeString(dir.resolve("channels/private.yml"), """
            name: private.owner
            receive-permission: cht.private.receive
            """);

        ChannelRegistry registry = ConfigLoader.loadChannels(dir);

        assertEquals(2, registry.all().size());
        Channel chat = registry.get("chat").orElseThrow();
        assertEquals("cht.chat", chat.permission());
        assertEquals("cht.chat.send", chat.sendPolicy());
        assertEquals(Language.ES, chat.langTarget());
        assertEquals(3, chat.rateLimitPerSecond());
        assertEquals(1, chat.messages().formats().length);
        assertEquals(1, chat.sounds().size());
        assertTrue(registry.resolve("private.owner.extra").name().equals("private.owner"));
    }

    @Test
    void returnsEmptyRegistryWithoutChannelsDir() {
        ChannelRegistry registry = ConfigLoader.loadChannels(dir);

        assertEquals(0, registry.all().size());
        assertEquals("chat", registry.resolve("anything").name());
    }

    @Test
    void parsesEditorExportedDefaultConfig() throws Exception {
        Path cfg = Path.of(ConfigLoaderTest.class
            .getResource("/editor-default/config").toURI());
        HostConfig config = ConfigLoader.loadConfig(cfg);
        ChannelRegistry registry = ConfigLoader.loadChannels(cfg);

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