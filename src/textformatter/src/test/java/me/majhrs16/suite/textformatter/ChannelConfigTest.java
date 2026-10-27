package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelConfigTest {

    @TempDir
    Path dir;

    private Path canonicalChannels() throws Exception {
        Path channels = dir.resolve("config/textformatter/channels");
        Files.createDirectories(channels);
        return channels;
    }

    @Test
    void loadsTemplatesFromCanonicalLocation() throws Exception {
        Path channels = canonicalChannels();
        Files.writeString(channels.resolve("chat.yml"), """
            name: chat
            messages:
              - '<gray>%player_name%: <tr>%content%</tr></gray>'
            """);

        ChannelRegistry registry = ChannelConfig.load(dir).config();
        Channel chat = registry.get("chat").orElseThrow();

        // Las plantillas van al array de formatos; si van a los textos el
        // formatter cae en el placeholder y entrega el contenido crudo.
        assertEquals(1, chat.messages().formats().length);
        assertEquals(0, chat.messages().texts().length);
        assertEquals("<gray>%player_name%: <tr>%content%</tr></gray>",
            chat.messages().format(0));
    }

    @Test
    void absentOrBlankPermissionsAreAcceptNotEmptyString() throws Exception {
        Path channels = canonicalChannels();
        Files.writeString(channels.resolve("absent.yml"), """
            name: absent
            messages:
              - '<gray>%content%</gray>'
            """);
        Files.writeString(channels.resolve("blank.yml"), """
            name: blank
            permission: ""
            send-permission: "  "
            receive-permission:
            """);

        ChannelRegistry registry = ChannelConfig.load(dir).config();

        for (String name : new String[]{"absent", "blank"}) {
            Channel channel = registry.get(name).orElseThrow();
            assertNull(channel.permission(), name + ".permission");
            assertNull(channel.sendPolicy(), name + ".sendPolicy");
            assertNull(channel.receivePolicy(), name + ".receivePolicy");
        }
    }

    @Test
    void readsExplicitPermissionsAndType() throws Exception {
        Path channels = canonicalChannels();
        Files.writeString(channels.resolve("private.yml"), """
            name: private.owner
            permission: cht.chat
            send-permission: cht.chat.send
            receive-permission: cht.chat.receive
            type: chat
            rate-limit-per-second: 3
            lang-target: es
            """);

        Channel channel = ChannelConfig.load(dir).config().get("private.owner").orElseThrow();

        assertEquals("cht.chat", channel.permission());
        assertEquals("cht.chat.send", channel.sendPolicy());
        assertEquals("cht.chat.receive", channel.receivePolicy());
        assertEquals(Channel.Type.CHAT, channel.type());
        assertEquals(3, channel.rateLimitPerSecond());
    }

    @Test
    void unknownTypeFallsBackToChatAndMissingNameUsesFileName() throws Exception {
        Path channels = canonicalChannels();
        Files.writeString(channels.resolve("weird.yml"), """
            type: no-existe
            messages:
              - '<gray>%content%</gray>'
            """);

        Channel channel = ChannelConfig.load(dir).config().get("weird").orElseThrow();

        assertEquals(Channel.Type.CHAT, channel.type());
        assertEquals("weird", channel.name());
    }

    @Test
    void readsSoundsAsStringAndAsMap() throws Exception {
        Path channels = canonicalChannels();
        Files.writeString(channels.resolve("chat.yml"), """
            name: chat
            sounds:
              - entity.experience_orb.pickup
              - name: block.note_block.pling
                volume: 0.5
                pitch: 1.2
            """);

        Channel chat = ChannelConfig.load(dir).config().get("chat").orElseThrow();

        assertEquals(2, chat.sounds().size());
        assertEquals("entity.experience_orb.pickup", chat.sounds().get(0).name());
        assertEquals(1.0f, chat.sounds().get(0).volume());
        assertEquals(0.5f, chat.sounds().get(1).volume());
        assertEquals(1.2f, chat.sounds().get(1).pitch());
    }

    @Test
    void resolvesLegacyLocationsDuringMigration() throws Exception {
        Path legacy = dir.resolve("channels");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("chat.yml"), """
            name: chat
            """);

        assertEquals(legacy, ChannelConfig.resolveChannelsDir(dir));
        assertTrue(ChannelConfig.load(dir).config().get("chat").isPresent());
    }

    @Test
    void canonicalLocationWinsOverLegacy() throws Exception {
        Path canonical = canonicalChannels();
        Files.writeString(canonical.resolve("chat.yml"), "name: chat\nmessages:\n  - canónica\n");
        Path legacy = dir.resolve("channels");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("chat.yml"), "name: chat\nmessages:\n  - legacy\n");

        Channel chat = ChannelConfig.load(dir).config().get("chat").orElseThrow();

        assertEquals("canónica", chat.messages().format(0));
    }

    @Test
    void missingDirectoryYieldsEmptyRegistryWithError() {
        ChannelConfig.LoadResult<ChannelRegistry> result = ChannelConfig.load(dir);

        assertFalse(result.isValid());
        assertTrue(result.config().all().isEmpty());
        assertEquals(1, result.errors().size());
    }
}
