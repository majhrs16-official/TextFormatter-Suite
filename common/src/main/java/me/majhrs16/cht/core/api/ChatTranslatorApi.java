package me.majhrs16.cht.core.api;

import me.majhrs16.cht.core.event.MessageEventBus;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.ChatMessage;
import me.majhrs16.cht.core.player.Subject;

/**
 * Public API offered to external integrations (CoT scripting, other plugins
 * and mods). Implemented by {@code ChatTranslatorApp}.
 */
public interface ChatTranslatorApi {

    /**
     * Injects a message into the routing pipeline.
     *
     * @param message the neutral message to route.
     */
    void sendMessage(ChatMessage message);

    /**
     * @return the message event bus; register {@code MessageListener}s here to
     *         intercept, mutate or cancel messages before they are routed.
     */
    MessageEventBus messageEvents();

    /**
     * @param subject a player (or console).
     * @return the subject's configured language, or the server default.
     */
    Language languageOf(Subject subject);

    /**
     * Stores the language preference of a player.
     *
     * @param subject the player.
     * @param language the language to persist.
     */
    void setLanguage(Subject subject, Language language);

    /**
     * @return the server default language.
     */
    Language defaultLanguage();

    /**
     * Reloads configuration files.
     */
    void reload();
}