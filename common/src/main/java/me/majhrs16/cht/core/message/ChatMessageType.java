package me.majhrs16.cht.core.message;

/**
 * The semantic kind of a message. Each kind is associated with a format group
 * in the {@code formats.yml} catalog and, in the platform adapters, with the
 * corresponding native event or command.
 */
public enum ChatMessageType {

    /** A regular public chat message. */
    CHAT,

    /** A private message (tell) sent to a single player. */
    PRIVATE_CHAT,

    /** A mention of another player inside a chat message. */
    MENTION,

    /** A player joining the server. */
    JOIN,

    /** A player leaving the server. */
    LEAVE,

    /** A player death message. */
    DEATH,

    /** A player achieving an advancement. */
    ADVANCEMENT,

    /** A sign being translated by interaction. */
    SIGN,

    /** Internal plugin messages (startup banner, errors, commands output). */
    INTERNAL,
}
