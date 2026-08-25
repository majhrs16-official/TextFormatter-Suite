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
 * @param claimMode        how the platform host takes over vanilla chat.
 */
public record HostConfig(
        boolean quickLook,
        Language defaultLanguage,
        boolean engineParallel,
        boolean soundEnabled,
        ClaimMode claimMode
) {

    /** How the adapter claims a vanilla chat event. */
    public enum ClaimMode {
        /** Cancel the event; the engine owns every copy. */
        CANCEL_EVENT,
        /** Leave the event alive but empty its recipient set (vanilla logs
         *  to console only); the engine owns player delivery. */
        CLEAR_RECIPIENTS,
    }

    public static HostConfig defaults() {
        return new HostConfig(true, Language.EN, false, true, ClaimMode.CANCEL_EVENT);
    }
}
