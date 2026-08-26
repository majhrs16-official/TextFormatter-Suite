package me.majhrs16.suite.spigothost.logic;

import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.util.UUID;

/** Pure rules for typed events and per-sender translation gating. */
public final class EventRules {

    public static final String CHANNEL_JOIN = "join";
    public static final String CHANNEL_QUIT = "quit";
    public static final String CHANNEL_DEATH = "death";

    private EventRules() {
    }

    /** Presence IS configuration: a typed event fires only if its channel exists. */
    public static boolean typedEventEnabled(ChannelRegistry registry, String conventionalName) {
        return registry.has(conventionalName);
    }

    /** Sender with stored {@code off} sends literal messages to everyone. */
    public static boolean shouldTranslate(UserLanguageStore store, UUID sender) {
        if (store == null || sender == null) {
            return true;
        }
        String stored = store.languageOf(sender).orElse(null);
        return !UserLanguageStore.OFF.equalsIgnoreCase(stored);
    }
}
