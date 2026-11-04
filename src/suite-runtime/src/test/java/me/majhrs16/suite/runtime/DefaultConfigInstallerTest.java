package me.majhrs16.suite.runtime;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.majhrs16.suite.api.spi.PluginLogger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultConfigInstallerTest {

    @TempDir
    Path dir;

    private final List<String> logs = new ArrayList<>();
    private final PluginLogger logger = new PluginLogger() {
        @Override public void info(String message, Object... args) { logs.add(message); }
        @Override public void warn(String message, Object... args) { logs.add(message); }
        @Override public void error(String message, Object... args) { logs.add(message); }
        @Override public void error(String message, Throwable throwable) { logs.add(message); }
        @Override public void debug(String message, Object... args) { logs.add(message); }
    };

    @Test
    void writesHostAndModuleDefaultsToTheirCanonicalRoots() throws Exception {
        install(allDefaults());

        assertThat(dir.resolve("config/suite/config.yml")).hasContent("quick-look: true\n");
        assertThat(dir.resolve("config/suite/commands.yml")).hasContent("root: [suite]\n");
        assertThat(dir.resolve("config/suite/messages.yml")).hasContent("prefix: suite\n");
        assertThat(dir.resolve("config/textformatter/channels/chat.global.yml"))
                .hasContent("path: chat.global\n");
        assertThat(dir.resolve("config/chattranslator/translators/google.yml"))
                .hasContent("provider: google\n");
        assertThat(dir.resolve("config/sync-discord/config.yml"))
                .hasContent("enabled: false\n");
        assertThat(dir.resolve("config/sync-websocket/config.yml"))
                .hasContent("enabled: false\n");
        assertThat(dir.resolve("config/inworld/config.yml"))
                .hasContent("enabled: false\n");
        assertThat(dir.resolve("config/iflow-firewall/rules.yml"))
                .hasContent("rules: []\n");
        assertThat(dir.resolve("config/iflow/config.yml"))
                .hasContent("engine: iflow-firewall\n");
        assertThat(dir.resolve("config/manager")).doesNotExist();
    }

    @Test
    void migratesLegacyModuleConfigToCanonicalPath() throws Exception {
        Path legacy = dir.resolve("channels/chat.global.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "path: legacy\n");

        install(Map.of("/defaults/channels/chat.global.yml", "path: default\n"));

        assertThat(dir.resolve("config/textformatter/channels/chat.global.yml"))
                .hasContent("path: legacy\n");
        assertThat(legacy).hasContent("path: legacy\n");
    }

    @Test
    void repairsIncorrectManagerConfigPathToCanonicalPath() throws Exception {
        Path previous = dir.resolve("manager/config/textformatter/channels/chat.global.yml");
        Files.createDirectories(previous.getParent());
        Files.writeString(previous, "path: previous\n");

        install(Map.of("/defaults/channels/chat.global.yml", "path: default\n"));

        assertThat(dir.resolve("config/textformatter/channels/chat.global.yml"))
                .hasContent("path: previous\n");
        assertThat(previous).doesNotExist();
    }

    @Test
    void repairsIncorrectConfigManagerPathToCanonicalPath() throws Exception {
        Path previous = dir.resolve("config/manager/textformatter/channels/chat.global.yml");
        Files.createDirectories(previous.getParent());
        Files.writeString(previous, "path: previous\n");

        install(Map.of("/defaults/channels/chat.global.yml", "path: default\n"));

        assertThat(dir.resolve("config/textformatter/channels/chat.global.yml"))
                .hasContent("path: previous\n");
        assertThat(previous).doesNotExist();
    }

    @Test
    void canonicalModuleConfigWinsOverLegacyAndDefault() throws Exception {
        Path canonical = dir.resolve("config/textformatter/channels/chat.global.yml");
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, "path: canonical\n");
        Path previous = dir.resolve("config/manager/textformatter/channels/chat.global.yml");
        Files.createDirectories(previous.getParent());
        Files.writeString(previous, "path: previous\n");
        Path legacy = dir.resolve("channels/chat.global.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "path: legacy\n");

        install(Map.of("/defaults/channels/chat.global.yml", "path: default\n"));

        assertThat(canonical).hasContent("path: canonical\n");
    }

    @Test
    void migratesLegacySuiteConfigToCanonicalPath() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "quick-look: false\n");

        install(Map.of("/defaults/config.yml", "quick-look: true\n"));

        assertThat(dir.resolve("config/suite/config.yml"))
                .hasContent("quick-look: false\n");
    }

    private void install(Map<String, String> resources) {
        Function<String, InputStream> opener = path -> {
            String content = resources.get(path);
            return content == null ? null
                    : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        };
        DefaultConfigInstaller.install(dir, opener, logger);
    }

    private static Map<String, String> allDefaults() {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("/defaults/config.yml", "quick-look: true\n");
        resources.put("/defaults/commands.yml", "root: [suite]\n");
        resources.put("/defaults/messages.yml", "prefix: suite\n");
        resources.put("/defaults/channels/chat.global.yml", "path: chat.global\n");
        resources.put("/defaults/channels/join.yml", "path: join\n");
        resources.put("/defaults/channels/quit.yml", "path: quit\n");
        resources.put("/defaults/channels/death.yml", "path: death\n");
        resources.put("/defaults/channels/advancement.yml", "path: advancement\n");
        resources.put("/defaults/translators/google.yml", "provider: google\n");
        resources.put("/defaults/sync/discord.yml", "enabled: false\n");
        resources.put("/defaults/sync/websocket.yml", "enabled: false\n");
        resources.put("/defaults/inworld.yml", "enabled: false\n");
        resources.put("/defaults/rules.yml", "rules: []\n");
        resources.put("/defaults/iflow.yml", "engine: iflow-firewall\n");
        return resources;
    }
}
