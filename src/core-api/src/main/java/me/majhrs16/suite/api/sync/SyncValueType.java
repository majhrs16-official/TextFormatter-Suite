package me.majhrs16.suite.api.sync;

/**
 * Neutral value types accepted by a sync action.
 *
 * <p>No SDK type crosses this boundary. {@link #OBJECT} is a nested map that
 * the backend validates; the core never interprets its content.</p>
 */
public enum SyncValueType {
    STRING,
    INTEGER,
    BOOLEAN,
    DURATION,
    ACTOR_REF,
    CHANNEL_REF,
    MESSAGE_REF,
    ROLE_REF,
    OBJECT
}
