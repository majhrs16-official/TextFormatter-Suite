package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.message.Language;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePresetTargetTest {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    @TempDir
    Path tempDir;

    @Test
    void persistsEveryPartInTheCanonicalLayout() throws IOException {
        Preset.PresetChannel channel = new Preset.PresetChannel(
            "chat.global", "CHAT", "suite.chat", "suite.send", "suite.receive",
            true, 3, "en", "es",
            List.of("<green>%content%</green>"), List.of("<gray>tip</gray>"),
            List.of(new Preset.PresetSound("bell", 0.8f, 1.2f)));
        Preset preset = new Preset(
            "demo", "Demo", "", "", "1.0.0", "2.1.0", List.of(),
            new Preset.PresetConfig(true, "es", true, false, "clear-recipients"),
            List.of(channel),
            List.of(new Preset.PresetTranslator("libre", true, "https://libre", "key", 4)),
            List.of(
                new Preset.PresetSync("discord", true, Map.of("token", "t", "channel", "42")),
                new Preset.PresetSync("telegram", true, Map.of("token", "tg", "chat-id", "7")),
                new Preset.PresetSync("http", true, Map.of(
                    "webhook-url", "https://hook", "inbound-port", "8080", "path", "/in")),
                new Preset.PresetSync("tcp-udp", true, Map.of(
                    "protocol", "UDP", "host", "127.0.0.1", "outbound-port", "9000")),
                new Preset.PresetSync("velocity", true, Map.of(
                    "secret", "s", "servers", "lobby, survival", "mapping", "* -> chat.hub"))
            )
        );

        preset.apply(new FilePresetTarget(tempDir));

        Map<String, Object> suite = yaml(tempDir.resolve("config/suite/config.yml"));
        assertThat(suite.get("quick-look")).isEqualTo(true);
        assertThat(nested(suite, "general", "language")).isEqualTo("es");
        assertThat(nested(suite, "iflow", "engine", "parallel")).isEqualTo(true);
        assertThat(nested(suite, "sonido", "enabled")).isEqualTo(false);
        assertThat(nested(suite, "chat", "claim-mode")).isEqualTo("clear-recipients");

        Map<String, Object> channelYaml = yaml(
            tempDir.resolve("config/textformatter/channels/chat.global.yml"));
        assertThat(channelYaml.get("name")).isEqualTo("chat.global");
        assertThat(channelYaml.get("type")).isEqualTo("CHAT");
        assertThat(channelYaml.get("permission")).isEqualTo("suite.chat");
        assertThat(channelYaml.get("lang-source")).isEqualTo("en");
        assertThat(channelYaml.get("lang-target")).isEqualTo("es");
        assertThat(channelYaml.get("messages")).isEqualTo(List.of("<green>%content%</green>"));
        assertThat(channelYaml.get("tooltips")).isEqualTo(List.of("<gray>tip</gray>"));
        assertThat((List<?>) channelYaml.get("sounds")).hasSize(1);

        Map<String, Object> translator = yaml(
            tempDir.resolve("config/chattranslator/translators/libre.yml"));
        assertThat(translator.get("provider")).isEqualTo("libre");
        assertThat(translator.get("active")).isEqualTo(true);
        assertThat(translator.get("base-url")).isEqualTo("https://libre");
        assertThat(translator.get("api-key")).isEqualTo("key");

        assertThat(yaml(tempDir.resolve("config/sync-discord/config.yml")))
            .containsEntry("token", "t")
            .containsEntry("channel", "42");
        assertThat(yaml(tempDir.resolve("config/sync-telegram/config.yml")))
            .containsEntry("chatId", "7");
        assertThat(yaml(tempDir.resolve("config/sync-http/config.yml")))
            .containsEntry("outboundUrl", "https://hook")
            .containsEntry("inboundPort", "8080")
            .containsEntry("inboundPath", "/in");
        assertThat(yaml(tempDir.resolve("config/sync-socket/udp.yml")))
            .containsEntry("remoteHost", "127.0.0.1")
            .containsEntry("remotePort", "9000");
        assertThat(yaml(tempDir.resolve("config/sync-velocity-core/config.yml")))
            .containsEntry("secret", "s")
            .containsEntry("mapping", "* -> chat.hub")
            .containsEntry("servers", List.of("lobby", "survival"));
    }

    @Test
    void mergesWithExistingConfigurationInsteadOfReplacingIt() throws IOException {
        Path suiteFile = tempDir.resolve("config/suite/config.yml");
        Files.createDirectories(suiteFile.getParent());
        Files.writeString(suiteFile, """
            general:
              language: en
              keep: untouched
            chat:
              log-to-console: false
            """);

        new FilePresetTarget(tempDir).applyConfig(
            new Preset.PresetConfig(false, "es", false, true, "cancel-event"));

        Map<String, Object> suite = yaml(suiteFile);
        assertThat(nested(suite, "general", "language")).isEqualTo("es");
        assertThat(nested(suite, "general", "keep")).isEqualTo("untouched");
        assertThat(nested(suite, "chat", "log-to-console")).isEqualTo(false);
        assertThat(nested(suite, "chat", "claim-mode")).isEqualTo("cancel-event");
    }

    @Test
    void rejectsUnknownSyncTypesInsteadOfIgnoringThem() {
        FilePresetTarget target = new FilePresetTarget(tempDir);
        Preset.PresetSync unknown = new Preset.PresetSync("matrix", true, Map.of());

        assertThatThrownBy(() -> target.applySync(unknown))
            .isInstanceOf(PresetApplyException.class)
            .hasMessageContaining("matrix");
    }

    @Test
    void appliesAStandardPresetThroughPresetManager() {
        PresetManager manager = new PresetManager(tempDir.resolve("presets"));

        assertThat(manager.apply("standard", new FilePresetTarget(tempDir))).isTrue();

        assertThat(tempDir.resolve("config/textformatter/channels/chat.global.yml")).exists();
        assertThat(tempDir.resolve("config/chattranslator/translators/google.yml")).exists();
        assertThat(tempDir.resolve("config/sync-discord/config.yml")).exists();
        assertThat(tempDir.resolve("config/sync-telegram/config.yml")).exists();
        assertThat(tempDir.resolve("config/sync-http/config.yml")).exists();
        assertThat(tempDir.resolve("config/sync-socket/tcp.yml")).exists();
        assertThat(tempDir.resolve("config/sync-velocity-core/config.yml")).exists();
    }

    @Test
    void writesChannelTemplatesAsFormatsSoTheFormatterCanConsumeThem() {
        Channel channel = Channel.builder("chat.test")
            .messages(new me.majhrs16.suite.api.message.Formats(
                new String[0], new String[] {"<white>%content%</white>"}))
            .langSource(Language.EN)
            .langTarget(Language.ES)
            .build();

        new FilePresetTarget(tempDir).applyChannel(channel);

        Map<String, Object> yaml = yaml(
            tempDir.resolve("config/textformatter/channels/chat.test.yml"));
        assertThat(yaml.get("messages")).isEqualTo(List.of("<white>%content%</white>"));
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(Path file) {
        try {
            Object loaded = YAML.load(Files.readString(file));
            assertThat(loaded).isInstanceOf(Map.class);
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
        }
    }
}
