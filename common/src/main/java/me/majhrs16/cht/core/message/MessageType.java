package me.majhrs16.cht.core.message;

/**
 * The semantic kind of a message. Each kind maps to a default routing behaviour
 * and, in the platform adapters, to the corresponding native event or command.
 */
public enum MessageType {

    /** A regular public chat message. */
    CHAT,

    /** A private (tell) message. */
    PRIVATE,

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

    /** Internal plugin messages (startup banner, errors, command output). */
    INTERNAL,

    /** A custom message created by the API or rules engine. */
    CUSTOM,
}