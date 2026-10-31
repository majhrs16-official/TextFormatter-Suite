package me.majhrs16.suite.synccommon;

import java.util.Collection;
import java.util.function.Predicate;

import me.majhrs16.suite.api.channel.Channel;

/**
 * Selects the chat channel for one sender.
 *
 * <p>Only {@link Channel.Type#CHAT} channels participate. The first channel
 * whose base permission is absent or granted wins; when none matches, the
 * registry fallback {@code chat} is used. Richer per-source routing belongs to
 * the iFlow rules, not to the platform listener.</p>
 */
public final class ChannelSelector {

    /** Channel path used when no configured chat channel accepts the sender. */
    public static final String FALLBACK = "chat";

    private ChannelSelector() {
    }

    /**
     * @param orderedChannels channels in registry order
     * @param hasPermission   permission test for the sender
     * @return effective channel path, never {@code null}
     */
    public static String select(Collection<Channel> orderedChannels, Predicate<String> hasPermission) {
        if (orderedChannels == null) {
            return FALLBACK;
        }
        for (Channel channel : orderedChannels) {
            if (channel.type() != Channel.Type.CHAT) {
                continue;
            }
            String permission = channel.permission();
            if (permission == null || permission.isBlank() || hasPermission.test(permission)) {
                return channel.name();
            }
        }
        return FALLBACK;
    }
}
