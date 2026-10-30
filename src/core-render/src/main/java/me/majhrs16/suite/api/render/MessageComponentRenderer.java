package me.majhrs16.suite.api.render;

import java.util.Objects;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;

import net.kyori.adventure.text.Component;

/**
 * Neutral rich-text render port for the message pipeline.
 *
 * <p>Lives in {@code core-render}, not {@code core-api}, because the contract
 * carries an Adventure {@link Component}. The core stays dependency-free and
 * features implement this port without importing host internals.</p>
 *
 * <p>The renderer receives the already-resolved source and target languages.
 * Implementations must not perform routing, permission checks or delivery.</p>
 */
public interface MessageComponentRenderer {

    /**
     * Renders one message for one recipient.
     *
     * @param message        message being rendered
     * @param recipient      actor that will receive the output
     * @param sourceLanguage resolved source language; never {@code AUTO}
     * @param targetLanguage resolved recipient language; never {@code AUTO}
     * @return rendered component, never {@code null}
     */
    Component render(Message message, Actor recipient,
                     Language sourceLanguage, Language targetLanguage);

    /**
     * Convenience overload that falls back to the message source and the
     * recipient language when a caller has no resolved languages.
     *
     * @param message   message being rendered
     * @param recipient actor that will receive the output
     * @return rendered component, never {@code null}
     */
    default Component render(Message message, Actor recipient) {
        Objects.requireNonNull(message, "message");
        Language source = message.langSource();
        if (source == null || source == Language.AUTO) {
            source = message.sender() != null ? message.sender().language() : null;
        }
        if (source == null || source == Language.AUTO) {
            source = Language.EN;
        }
        Language target = recipient != null ? recipient.language() : null;
        if (target == null || target == Language.AUTO) {
            target = source;
        }
        return render(message, recipient, source, target);
    }
}
