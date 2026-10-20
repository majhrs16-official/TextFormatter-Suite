package me.majhrs16.suite.presets;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetTest {

    @Test
    void exposesPresetMetadataAndNestedValues() {
        Preset.PresetSound sound = new Preset.PresetSound("bell", 0.8f, 1.2f);
        Preset.PresetChannel channel = new Preset.PresetChannel(
            "global", "CHAT", "suite.chat", "suite.send", "suite.receive",
            true, 5, "en", "es", List.of("hello"), List.of("tip"), List.of(sound)
        );
        Preset.PresetConfig config = new Preset.PresetConfig(true, "es", false, true, "auto");
        Preset preset = new Preset(
            "id", "Name", "Description", "Author", "1.0.0", "2.1.0",
            List.of("chat"), config, List.of(channel), List.of(), List.of(), List.of()
        );

        assertEquals("id", preset.id());
        assertEquals("Name", preset.name());
        assertEquals("Description", preset.description());
        assertEquals("Author", preset.author());
        assertEquals("1.0.0", preset.version());
        assertEquals("2.1.0", preset.minSuiteVersion());
        assertEquals(List.of("chat"), preset.tags());
        assertEquals(config, preset.config());
        assertEquals(1, preset.channels().size());

        assertEquals("global", channel.name());
        assertEquals("CHAT", channel.type());
        assertEquals(5, channel.rateLimitPerSecond());
        assertTrue(channel.showSender());
        assertEquals("bell", channel.sounds().get(0).name());
        assertEquals(0.8f, channel.sounds().get(0).volume());
        assertEquals(1.2f, channel.sounds().get(0).pitch());

        assertTrue(config.quickLook());
        assertEquals("es", config.language());
        assertEquals("auto", config.claimMode());
    }

    @Test
    void applyIsSafeForAnEmptyPreset() {
        Preset preset = new Preset(
            "empty", "Empty", "", "", "1.0.0", "2.1.0",
            List.of(), null, List.of(), List.of(), List.of(), List.of()
        );

        preset.apply(null, null, null);

        assertEquals("empty", preset.id());
        assertTrue(preset.channels().isEmpty());
    }
}
