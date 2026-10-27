package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.PluginLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {

    @TempDir
    Path dir;

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) { }
            @Override public void warn(String m, Object... a) { warnings.add(String.format(m, a)); }
            @Override public void error(String m, Object... a) { errors.add(String.format(m, a)); }
            @Override public void error(String m, Throwable t) { errors.add(m + " :: " + t); }
            @Override public void debug(String m, Object... a) { }
        };
    }

    private void writeChannel(String name, String yaml) throws Exception {
        Path channels = dir.resolve("config/textformatter/channels");
        Files.createDirectories(channels);
        Files.writeString(channels.resolve(name + ".yml"), yaml);
    }

    private void writeDefaultChannels() throws Exception {
        for (String name : new String[]{"chat.global", "join", "quit", "death", "advancement"}) {
            writeChannel(name, """
                name: %s
                type: chat
                messages:
                  - '<gray>%%content%%</gray>'
                died: null
                """.formatted(name));
        }
    }

    @Test
    void canonicalLayoutIsValid() throws Exception {
        Files.createDirectories(dir.resolve("config/suite"));
        Files.writeString(dir.resolve("config/suite/config.yml"), """
            quick-look: true
            general:
              language: en
            chat:
              claim-mode: cancel-event
            """);
        writeDefaultChannels();
        Path translators = dir.resolve("config/chattranslator/translators");
        Files.createDirectories(translators);
        Files.writeString(translators.resolve("google.yml"), """
            provider: google
            active: true
            """);

        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        List<ConfigValidator.ValidationIssue> errorIssues = issues.stream()
            .filter(i -> i.level() == ConfigValidator.ValidationLevel.ERROR)
            .toList();
        assertTrue(errorIssues.isEmpty(), "sin errores en layout canónico: " + errorIssues);
        assertTrue(errors.isEmpty());
    }

    @Test
    void emptyLayoutWarnsButDoesNotError() {
        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        assertTrue(issues.stream().anyMatch(i -> i.group().equals("config")
            && i.level() == ConfigValidator.ValidationLevel.WARNING));
        assertTrue(issues.stream().anyMatch(i -> i.group().equals("channels")
            && i.level() == ConfigValidator.ValidationLevel.WARNING));
        assertTrue(issues.stream().anyMatch(i -> i.group().equals("translators")
            && i.level() == ConfigValidator.ValidationLevel.WARNING));
        assertTrue(issues.stream().anyMatch(i -> i.group().equals("rules")
            && i.level() == ConfigValidator.ValidationLevel.INFO));
        assertTrue(issues.stream().noneMatch(i -> i.level() == ConfigValidator.ValidationLevel.ERROR));
    }

    @Test
    void invalidChannelFieldsAreReported() throws Exception {
        writeChannel("chat.global", """
            name: chat.global
            type: nonsense
            rate-limit-per-second: -3
            messages: "no-es-lista"
            tooltips: 42
            """);

        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        assertEquals(4, issues.stream()
            .filter(i -> i.level() == ConfigValidator.ValidationLevel.ERROR)
            .count(), issues.toString());
        assertTrue(issues.stream().anyMatch(i -> i.path().equals("channels/chat.global.type")));
        assertTrue(issues.stream().anyMatch(i -> i.path().equals("channels/chat.global.rate-limit-per-second")));
        assertTrue(issues.stream().anyMatch(i -> i.path().equals("channels/chat.global.messages")));
        assertTrue(issues.stream().anyMatch(i -> i.path().equals("channels/chat.global.tooltips")));
    }

    @Test
    void missingDefaultChannelsWarn() throws Exception {
        writeChannel("custom", "name: custom\n");

        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        long missingDefaults = issues.stream()
            .filter(i -> i.message().contains("canal por defecto"))
            .count();
        assertEquals(5, missingDefaults);
    }

    @Test
    void translatorWithoutProviderIsAnError() throws Exception {
        Path translators = dir.resolve("config/chattranslator/translators");
        Files.createDirectories(translators);
        Files.writeString(translators.resolve("broken.yml"), "active: true\n");

        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        assertTrue(issues.stream().anyMatch(i -> i.group().equals("translators")
            && i.level() == ConfigValidator.ValidationLevel.ERROR
            && i.message().contains("provider")));
    }

    @Test
    void unknownTranslatorProviderWarns() throws Exception {
        Path translators = dir.resolve("config/chattranslator/translators");
        Files.createDirectories(translators);
        Files.writeString(translators.resolve("deepl.yml"), """
            provider: deepl
            active: true
            """);

        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        assertTrue(issues.stream().anyMatch(i -> i.group().equals("translators")
            && i.level() == ConfigValidator.ValidationLevel.WARNING
            && i.message().contains("provider desconocido")));
    }

    @Test
    void legacyRulesLocationStillValidates() throws Exception {
        Files.writeString(dir.resolve("rules.yml"), """
            nodes:
              - id: start
                kind: input
              - id: start
                kind: output
            """);

        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        assertTrue(issues.stream().anyMatch(i -> i.group().equals("rules")
            && i.level() == ConfigValidator.ValidationLevel.ERROR
            && i.message().contains("id duplicado")));
    }

    @Test
    void canonicalRulesLocationWins() throws Exception {
        Path rules = dir.resolve("config/iflow-firewall");
        Files.createDirectories(rules);
        Files.writeString(rules.resolve("rules.yml"), """
            guard:
              max-steps: 0
            filter:
              dedup-fanout: "sí"
            """);
        Files.writeString(dir.resolve("rules.yml"), "nodes: []\n");

        List<ConfigValidator.ValidationIssue> issues =
            ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        assertTrue(issues.stream().anyMatch(i -> i.path().equals("guard.max-steps")
            && i.level() == ConfigValidator.ValidationLevel.ERROR));
        assertTrue(issues.stream().anyMatch(i -> i.path().equals("filter.dedup-fanout")
            && i.level() == ConfigValidator.ValidationLevel.WARNING));
    }

    @Test
    void loggerReceivesEveryIssueLevel() throws Exception {
        writeChannel("chat.global", "type: nonsense\n");

        ConfigValidator.validate(HostConfig.defaults(), logger(), dir);

        assertFalse(errors.isEmpty(), "los ERROR deben llegar al logger");
        assertFalse(warnings.isEmpty(), "los WARNING deben llegar al logger");
    }
}
