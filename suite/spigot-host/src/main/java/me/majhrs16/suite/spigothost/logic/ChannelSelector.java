package me.majhrs16.suite.spigothost.logic;

import me.majhrs16.suite.textformatter.channel.Channel;

import java.util.List;
import java.util.function.Predicate;

/**
 * MVP channel selection, extracted from the Bukkit layer so it is unit-
 * testable: the first registered channel whose base permission is absent or
 * granted wins; falls back to {@code chat}. Richer per-source routing
 * belongs to iFlow rules (F7+).
 */
public final class ChannelSelector {

    public static final String FALLBACK = "chat";

    private ChannelSelector() {
    }

    /** @return effective channel path for a sender with {@code hasPermission}. */
    public static String select(List<Channel> orderedChannels, Predicate<String> hasPermission) {
        for (Channel channel : orderedChannels) {
            String permission = channel.permission();
            if (permission == null || hasPermission.test(permission)) {
                return channel.name();
            }
        }
        return FALLBACK;
    }
}
