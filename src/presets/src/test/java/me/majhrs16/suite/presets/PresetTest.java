package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.message.Language;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            List.of("chat"), config, List.of(channel), List.of(), List.of()
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
    void appliesEveryPartThroughTheTarget() {
        Preset.PresetSound sound = new Preset.PresetSound("bell", 0.8f, 1.2f);
        Preset.PresetChannel channel = new Preset.PresetChannel(
            "staff.chat", "EVENT", "", "staff.send", "",
            false, 5, "en", "es", List.of("hello"), List.of("tip"), List.of(sound)
        );
        Preset.PresetConfig config = new Preset.PresetConfig(true, "es", false, true, "auto");
        Preset.PresetTranslator translator = new Preset.PresetTranslator(
            "google", true, "https://example", "key", 4);
        Preset.PresetSync sync = new Preset.PresetSync(
            "http", true, java.util.Map.of("url", "https://example"));
        Preset preset = new Preset(
            "id", "Name", "Description", "Author", "1.0.0", "2.1.0",
            List.of("chat"), config, List.of(channel), List.of(translator), List.of(sync)
        );

        RecordingPresetTarget target = new RecordingPresetTarget();
        preset.apply(target);

        assertEquals(List.of(config), target.configs);
        assertEquals(1, target.channels.size());
        assertEquals(List.of(translator), target.translators);
        assertEquals(List.of(sync), target.sync);

        Channel applied = target.channels.get(0);
        assertEquals("staff.chat", applied.name());
        assertEquals(Channel.Type.EVENT, applied.type());
        assertNull(applied.permission());
        assertEquals("staff.send", applied.sendPermission());
        assertNull(applied.receivePermission());
        assertEquals(Language.EN, applied.langSource());
        assertEquals(Language.ES, applied.langTarget());
        assertEquals(5, applied.rateLimitPerSecond());
        assertTrue(applied.showSender() == false);
        assertEquals(1, applied.messages().size());
        assertEquals("hello", applied.messages().text(0));
        assertEquals(1, applied.tooltips().size());
        assertEquals("tip", applied.tooltips().text(0));
        assertEquals(1, applied.sounds().size());
        assertEquals("bell", applied.sounds().get(0).name());
    }

    @Test
    void emptyPresetStillRequiresATarget() {
        Preset preset = new Preset(
            "empty", "Empty", "", "", "1.0.0", "2.1.0",
            List.of(), null, List.of(), List.of(), List.of()
        );

        RecordingPresetTarget target = new RecordingPresetTarget();
        preset.apply(target);

        assertTrue(target.configs.isEmpty());
        assertTrue(target.channels.isEmpty());
        assertThrows(NullPointerException.class, () -> preset.apply(null));
    }
}
