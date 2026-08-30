package me.majhrs16.suite.fabrichost.logic;

import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

/**
 * Shared logic for typed event dispatch (join/quit/death).
 */
public final class EventRules {

    public static final String CHANNEL_JOIN = "join";
    public static final String CHANNEL_QUIT = "quit";
    public static final String CHANNEL_DEATH = "death";

    private EventRules() {}

    public static boolean typedEventEnabled(ChannelRegistry registry, String channelName) {
        return registry.get(channelName).isPresent();
    }

    public static boolean shouldTranslate(UserLanguageStore store, java.util.UUID uuid) {
        return LangSetting.shouldTranslate(store, uuid);
    }
}
