package me.majhrs16.suite.presets;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests de integración para {@link PresetManager}.
 * Validan la carga, exportación y aplicación de presets.
 */
@DisplayName("PresetManager — integración")
class PresetManagerIntegrationTest {

    @TempDir
    Path tempDir;

    private PresetManager presetManager;

    @BeforeEach
    void setUp() {
        Path presetsDir = tempDir.resolve("presets");
        presetManager = new PresetManager(presetsDir);
    }

    @AfterEach
    void tearDown() {
        // No cleanup needed, temp dir is auto-cleaned
    }

    @Test
    @DisplayName("carga presets built-in correctamente")
    void loadsBuiltInPresets() {
        List<Preset> all = presetManager.listAll();
        
        assertThat(all).hasSizeGreaterThanOrEqualTo(4); // standard, rpg, staff, minimal
        
        Optional<Preset> standard = presetManager.get("standard");
        assertThat(standard).isPresent();
        assertThat(standard.get().name()).isEqualTo("Standard");
        assertThat(standard.get().channels()).hasSize(5); // chat.global, join, quit, death, advancement
        assertThat(standard.get().translators()).hasSize(2); // google, libre
        assertThat(standard.get().sync()).hasSize(5); // discord, telegram, http, tcp-udp, velocity

        Optional<Preset> rpg = presetManager.get("rpg");
        assertThat(rpg).isPresent();
        assertThat(rpg.get().name()).isEqualTo("RPG / Survival");
        assertThat(rpg.get().channels()).hasSize(7); // multiple chat channels + events

        Optional<Preset> staff = presetManager.get("staff");
        assertThat(staff).isPresent();
        assertThat(staff.get().name()).isEqualTo("Staff / Moderation");
        
        Optional<Preset> minimal = presetManager.get("minimal");
        assertThat(minimal).isPresent();
        assertThat(minimal.get().name()).isEqualTo("Minimal / Lightweight");
        assertThat(minimal.get().channels()).hasSize(1);
    }

    @Test
    @DisplayName("lista presets por tag")
    void listsPresetsByTag() {
        List<Preset> chatPresets = presetManager.listByTag("chat");
        assertThat(chatPresets).allMatch(p -> p.tags().contains("chat"));
        
        List<Preset> rpgPresets = presetManager.listByTag("rpg");
        assertThat(rpgPresets).hasSize(1);
        assertThat(rpgPresets.get(0).id()).isEqualTo("rpg");
    }

    @Test
    @DisplayName("carga preset personalizado desde YAML")
    void loadsCustomPresetFromYaml() throws IOException {
        Path presetsDir = tempDir.resolve("presets");
        Files.createDirectories(presetsDir);
        
        String yamlContent = """
            id: custom-test
            name: Custom Test
            description: A custom preset for testing
            author: Test Author
            version: 1.0.0
            minSuiteVersion: 2.1.0
            tags: [custom, test]
            config:
              quickLook: true
              language: en
              parallel: false
              soundEnabled: true
              claimMode: cancel-event
            channels:
              - name: chat.test
                type: CHAT
                permission: custom.test
                sendPermission: custom.send
                receivePermission: custom.receive
                showSender: true
                rateLimitPerSecond: 5
                langSource: auto
                langTarget: auto
                messages:
                  - "Test message"
                tooltips:
                  - "Test tooltip"
                sounds:
                  - name: entity.experience_orb.pickup
                    volume: 1.0
                    pitch: 1.0
            translators:
              - provider: google
                active: true
                baseUrl: ""
                apiKey: ""
                maxConcurrent: 6
            sync:
              - type: http
                enabled: true
                config:
                  webhook-url: "http://example.com"
                  inbound-port: "8080"
                  path: "/hook"
            """;
        
        Files.writeString(presetsDir.resolve("custom-test.yml"), yamlContent);
        
        // Create new manager to load the custom preset
        PresetManager newManager = new PresetManager(presetsDir);
        
        Optional<Preset> custom = newManager.get("custom-test");
        assertThat(custom).isPresent();
        assertThat(custom.get().name()).isEqualTo("Custom Test");
        assertThat(custom.get().tags()).contains("custom", "test");
        assertThat(custom.get().channels()).hasSize(1);
        assertThat(custom.get().channels().get(0).name()).isEqualTo("chat.test");
        assertThat(custom.get().translators()).hasSize(1);
        assertThat(custom.get().translators().get(0).provider()).isEqualTo("google");
        assertThat(custom.get().sync()).hasSize(1);
        assertThat(custom.get().sync().get(0).type()).isEqualTo("http");
    }

    @Test
    @DisplayName("exporta preset a YAML correctamente")
    void exportsPresetToYaml() throws IOException {
        Optional<Preset> standard = presetManager.get("standard");
        assertThat(standard).isPresent();
        
        Path outputFile = tempDir.resolve("exported-standard.yml");
        presetManager.export("standard", outputFile);
        
        assertThat(outputFile).exists();
        
        String content = Files.readString(outputFile);
        assertThat(content).contains("id: standard");
        assertThat(content).contains("name: Standard");
        assertThat(content).contains("channels:");
        assertThat(content).contains("translators:");
        assertThat(content).contains("sync:");
    }

    @Test
    @DisplayName("register y unregister presets personalizados")
    void registerAndUnregisterCustomPresets() {
        Preset custom = new Preset(
            "custom-id", "Custom", "Description", "Author", "1.0.0", "2.1.0",
            List.of("custom"), null, List.of(), List.of(), List.of()
        );
        
        // Register
        assertThat(presetManager.register(custom)).isTrue();
        assertThat(presetManager.get("custom-id")).isPresent();
        
        // Duplicate register should fail
        assertThat(presetManager.register(custom)).isFalse();
        
        // Unregister
        presetManager.unregister("custom-id");
        assertThat(presetManager.get("custom-id")).isEmpty();
    }

    @Test
    @DisplayName("apply preset aplica todas sus partes")
    void applyPresetAppliesEveryPart() {
        RecordingPresetTarget target = new RecordingPresetTarget();

        boolean result = presetManager.apply("standard", target);

        assertThat(result).isTrue();
        assertThat(target.configs).hasSize(1);
        assertThat(target.channels).hasSize(5);
        assertThat(target.translators).hasSize(2);
        assertThat(target.sync).hasSize(5);
        assertThat(target.channels.get(0).name()).isEqualTo("chat.global");
        assertThat(target.channels.get(0).messages().size()).isEqualTo(2);
    }

    @Test
    @DisplayName("apply preset inexistente devuelve false")
    void applyNonExistentPresetReturnsFalse() {
        RecordingPresetTarget target = new RecordingPresetTarget();
        boolean result = presetManager.apply("non-existent", target);
        assertThat(result).isFalse();
        assertThat(target.channels).isEmpty();
    }

    @Test
    @DisplayName("preset built-in tiene configuración completa")
    void builtInPresetHasCompleteConfig() {
        Optional<Preset> standard = presetManager.get("standard");
        assertThat(standard).isPresent();
        
        Preset.PresetConfig config = standard.get().config();
        assertThat(config.quickLook()).isTrue();
        assertThat(config.language()).isEqualTo("en");
        assertThat(config.parallel()).isFalse();
        assertThat(config.soundEnabled()).isTrue();
        assertThat(config.claimMode()).isEqualTo("cancel-event");
        
        // Check channels
        List<Preset.PresetChannel> channels = standard.get().channels();
        assertThat(channels).hasSize(5);
        
        // Check translators
        List<Preset.PresetTranslator> translators = standard.get().translators();
        assertThat(translators).hasSize(2);
        assertThat(translators.get(0).provider()).isEqualTo("google");
        assertThat(translators.get(0).active()).isTrue();
        assertThat(translators.get(1).provider()).isEqualTo("libre");
        assertThat(translators.get(1).active()).isFalse();
        
        // Check sync
        List<Preset.PresetSync> sync = standard.get().sync();
        assertThat(sync).hasSize(5);
    }

    @Test
    @DisplayName("preset RPG tiene canales de roleplay")
    void rpgPresetHasRoleplayChannels() {
        Optional<Preset> rpg = presetManager.get("rpg");
        assertThat(rpg).isPresent();
        
        List<Preset.PresetChannel> channels = rpg.get().channels();
        assertThat(channels).hasSize(7);
        
        // Check for RP channel
        boolean hasRpChannel = channels.stream()
            .anyMatch(c -> c.name().equals("chat.rp"));
        assertThat(hasRpChannel).isTrue();
        
        // Check for local channel
        boolean hasLocalChannel = channels.stream()
            .anyMatch(c -> c.name().equals("chat.local"));
        assertThat(hasLocalChannel).isTrue();
    }

    @Test
    @DisplayName("preset staff tiene sync de discord y http habilitados")
    void staffPresetHasDiscordAndHttpSyncEnabled() {
        Optional<Preset> staff = presetManager.get("staff");
        assertThat(staff).isPresent();
        
        List<Preset.PresetSync> sync = staff.get().sync();
        assertThat(sync).hasSize(2);
        
        Optional<Preset.PresetSync> discord = sync.stream()
            .filter(s -> s.type().equals("discord"))
            .findFirst();
        assertThat(discord).isPresent();
        assertThat(discord.get().enabled()).isTrue();
        
        Optional<Preset.PresetSync> http = sync.stream()
            .filter(s -> s.type().equals("http"))
            .findFirst();
        assertThat(http).isPresent();
        assertThat(http.get().enabled()).isTrue();
    }

    @Test
    @DisplayName("getAll devuelve colección inmutable")
    void getAllReturnsUnmodifiableCollection() {
        var all = presetManager.getAll();
        assertThat(all).hasSizeGreaterThanOrEqualTo(4);
        
        // Should be unmodifiable
        assertThatThrownBy(() -> all.add(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}