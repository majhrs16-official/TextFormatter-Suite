package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.channel.Channel;

import java.util.ArrayList;
import java.util.List;

/** Test target that records every application step. */
final class RecordingPresetTarget implements PresetTarget {

    final List<Preset.PresetConfig> configs = new ArrayList<>();
    final List<Channel> channels = new ArrayList<>();
    final List<Preset.PresetTranslator> translators = new ArrayList<>();
    final List<Preset.PresetSync> sync = new ArrayList<>();

    @Override
    public void applyConfig(Preset.PresetConfig config) {
        configs.add(config);
    }

    @Override
    public void applyChannel(Channel channel) {
        channels.add(channel);
    }

    @Override
    public void applyTranslator(Preset.PresetTranslator translator) {
        translators.add(translator);
    }

    @Override
    public void applySync(Preset.PresetSync syncEntry) {
        sync.add(syncEntry);
    }
}
