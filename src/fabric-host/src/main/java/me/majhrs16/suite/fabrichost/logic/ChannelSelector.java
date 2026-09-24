package me.majhrs16.suite.fabrichost.logic;

import me.majhrs16.suite.textformatter.channel.Channel;

import java.util.List;
import java.util.function.Predicate;

/**
 * Selects the appropriate channel for a chat message based on sender
 * permissions. Mirrors the Spigot implementation for parity.
 */
public final class ChannelSelector {

    public static final String FALLBACK = "chat.global";

    private ChannelSelector() {}

    /** @return effective channel path for a sender with {@code hasPermission}. */
    public static String select(List<Channel> orderedChannels, Predicate<String> hasPermission) {
        for (Channel channel : orderedChannels) {
            String permission = channel.permission();
            if (permission == null || permission.isBlank() || hasPermission.test(permission)) {
                return channel.name();
            }
        }
        return FALLBACK;
    }
}
