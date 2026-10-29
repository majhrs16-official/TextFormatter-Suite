package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.channel.Channel;

/**
 * Neutral side of {@link Preset#apply(PresetTarget)}.
 *
 * <p>A host adapter implements this port to persist the preset: write host
 * settings, register or write channels, enable translators and configure sync
 * transports. The preset module only decides <em>what</em> to apply; the
 * adapter owns file formats and platform reloads.</p>
 */
public interface PresetTarget {

    /** Applies host-level settings. */
    void applyConfig(Preset.PresetConfig config);

    /** Applies one channel definition. */
    void applyChannel(Channel channel);

    /** Applies one translator backend definition. */
    void applyTranslator(Preset.PresetTranslator translator);

    /** Applies one sync transport definition. */
    void applySync(Preset.PresetSync sync);
}
