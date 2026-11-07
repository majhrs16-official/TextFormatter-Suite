package me.majhrs16.suite.api.sync;

/**
 * Nature of a sync backend action.
 *
 * <p>The kind drives authorization, not routing: {@link #DESTRUCTIVE} and
 * {@link #MODERATION} require an explicit destructive grant, while
 * {@link #READ} and {@link #WRITE} only require the action capability.</p>
 */
public enum ActionKind {
    READ,
    WRITE,
    DESTRUCTIVE,
    MODERATION
}
