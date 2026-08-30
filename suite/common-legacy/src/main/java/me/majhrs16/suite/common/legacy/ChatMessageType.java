package me.majhrs16.suite.common.legacy;

/**
 * Deprecated minimal copy of the v1.8 {@code ChatMessageType}, self-contained
 * so the CoreTranslator bridge can be built without the legacy jar.
 */
@Deprecated
public enum ChatMessageType {
    CHAT,
    PRIVATE_CHAT,
    MENTION,
    JOIN,
    LEAVE,
    DEATH,
    ADVANCEMENT,
    SIGN,
    INTERNAL;
}
