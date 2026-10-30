package me.majhrs16.suite.api.inworld;

import java.util.List;
import java.util.Optional;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

/**
 * Neutral in-world domain published by the {@code inworld} package.
 *
 * <p>Platform adapters use this contract to translate signs, containers and
 * books into {@link Message}s without importing the feature implementation.</p>
 */
public interface InWorldService {

    /** @return the channel configured for the sign at the position, if any. */
    Optional<String> signChannel(String world, int x, int y, int z);

    /** @return the message for one non-empty sign line. */
    Optional<Message> signMessage(Actor actor, String channelName, String text);

    /** Stores the last rendered sign content. */
    void cacheSign(String world, int x, int y, int z, String channelName, String[] lines);

    /** @return the message emitted when a player opens a container. */
    Optional<Message> containerMessage(Actor actor, String channelName,
                                       String world, int x, int y, int z);

    /** @return the message emitted when a player reads a written book. */
    Optional<Message> bookMessage(Actor actor, String channelName,
                                  String title, List<String> pages);

    /** Dispatches a click/hover action through the platform port. */
    void handleClick(InWorldPlatform platform, Actor actor, String action, String value);

    /** @return the glossary text for a hover key. */
    String getHoverText(Actor actor, String key);
}
