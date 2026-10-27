package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.message.Language;

import java.util.List;

/**
 * Immutable, resolved view of {@code config.yml}. Every field has a sane
 * default so a partial file (or none) still produces a working host.
 *
 * @param quickLook         show an annotated echo to the sender (quick-look).
 * @param defaultLanguage   fallback UI/language code when nothing else is set.
 * @param engineParallel    iFlow knob: parallel recipient routing (new in v2.1).
 * @param engine            iFlow runtime: VM (`.flow`) or firewall (legacy SpEL rules).
 * @param soundEnabled      master switch for channel sounds.
 * @param claimMode         how the platform host takes over vanilla chat.
 * @param logChatToConsole  whether to also send player chat to server console.
 * @param repositories      list of module repositories (GitHub, local, HTTP)
 */
public record HostConfig(
        boolean quickLook,
        Language defaultLanguage,
        boolean engineParallel,
        Engine engine,
        boolean soundEnabled,
        ClaimMode claimMode,
        boolean logChatToConsole,
        List<Repository> repositories,
        List<String> moduleAllowlist
) {

    /** iFlow runtime selection; the engines are mutually exclusive alternatives. */
    public enum Engine {
        /** Binary {@code .flow} programs executed by {@code iflow-vm}. */
        VM,
        /** Legacy SpEL rules executed by {@code iflow-firewall} (alternative to the VM). */
        FIREWALL
    }

    /** How the adapter claims a vanilla chat event. */
    public enum ClaimMode {
        /** Cancel the event; the engine owns every copy. */
        CANCEL_EVENT,
        /** Leave the event alive but empty its recipient set (vanilla logs
         *  to console only); the engine owns player delivery. */
        CLEAR_RECIPIENTS,
        /** Don't suppress vanilla; both suite and vanilla chat appear. */
        NONE
    }

    /** Repository configuration for module downloads. */
    public record Repository(
            String name,
            String url,
            String type, // "github", "local", "http"
            boolean enabled
    ) {}

    public static HostConfig defaults() {
        return new HostConfig(true, Language.EN, false, Engine.VM, true, ClaimMode.CANCEL_EVENT, true, List.of(), List.of());
    }
}
