package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;

/**
 * Neutral, platform-free rendering port for plain-text output.
 *
 * <p>Adapters that need a rendered line (in-world signs, test probes, command
 * feedback) use this port instead of importing the textformatter package. The
 * formatter package provides the implementation; the adapter only sees
 * {@link Message}, {@link Actor} and {@link Language}.</p>
 */
@FunctionalInterface
public interface MessageRenderer {

    /**
     * Renders one message for a recipient as plain text.
     *
     * @param message         message to render
     * @param content         raw content to use as the template body
     * @param recipient       actor receiving the output
     * @param defaultLanguage fallback when the message has no explicit source
     * @return rendered plain text, never {@code null}
     */
    String render(Message message, String content, Actor recipient, Language defaultLanguage);
}
