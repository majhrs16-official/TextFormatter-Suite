package me.majhrs16.cht.core.player;

/**
 * The platform channel a message is sent to or received from.
 *
 * <p>The routing engine uses this to pick the correct adapter output and to
 * decide whether a recipient can receive a given message (e.g. a player that
 * joined after the message must still have it routed to console).</p>
 */
public enum Channel {

    /** In-game chat with rich formatting (hover, click, gradients). */
    CHAT,

    /** Server console output, plain text. */
    CONSOLE,

    /** An external platform, e.g. Discord. */
    DISCORD,

    /** A player only via private messaging (tell). */
    PRIVATE,
}
