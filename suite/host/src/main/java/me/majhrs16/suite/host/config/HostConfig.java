package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.message.Language;

/**
 * Immutable, resolved view of {@code config.yml}. Every field has a sane
 * default so a partial file (or none) still produces a working host.
 *
 * @param quickLook        show an annotated echo to the sender (quick-look).
 * @param defaultLanguage  fallback UI/language code when nothing else is set.
 * @param engineParallel   iFlow knob: parallel recipient routing (new in v2.1).
 * @param soundEnabled     master switch for channel sounds.
 */
public record HostConfig(
        boolean quickLook,
        Language defaultLanguage,
        boolean engineParallel,
        boolean soundEnabled
) {

    public static HostConfig defaults() {
        return new HostConfig(true, Language.EN, false, true);
    }
}