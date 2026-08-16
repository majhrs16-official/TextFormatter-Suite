package me.majhrs16.suite.coretranslator.legacy;

/**
 * Deprecated legacy message kind, kept for backward compatibility with
 * integrations written against ChatTranslator v1.8. New code should use
 * {@link me.majhrs16.suite.api.message.MessageType}.
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
    INTERNAL,
}