package me.majhrs16.suite.synccommon;

import java.util.UUID;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.spi.UserLanguageStore;

/**
 * Pure rules shared by the Bukkit and Fabric event capture packages.
 */
public final class EventRules {

    public static final String CHANNEL_JOIN = "join";
    public static final String CHANNEL_QUIT = "quit";
    public static final String CHANNEL_DEATH = "death";
    public static final String CHANNEL_ADVANCEMENT = "advancement";

    private EventRules() {
    }

    /**
     * Presence is configuration: a typed event is dispatched only when its
     * conventional channel exists in the active registry.
     */
    public static boolean typedEventEnabled(ChannelRegistry registry, String conventionalName) {
        return registry != null && registry.has(conventionalName);
    }

    /**
     * A sender with stored {@code off} sends literal messages to everyone.
     */
    public static boolean shouldTranslate(UserLanguageStore store, UUID sender) {
        if (store == null || sender == null) {
            return true;
        }
        String stored = store.languageOf(sender).orElse(null);
        return !UserLanguageStore.OFF.equalsIgnoreCase(stored);
    }
}
