package me.majhrs16.suite.iflow.target;

/**
 * Disposition the router applies to a message for one recipient.
 *
 * <ul>
 *   <li>{@link #LOG} — deliver and record the message in the log sink.</li>
 *   <li>{@link #DROP} — discard silently (no delivery, no log).</li>
 *   <li>{@link #REJECT} — discard and mark the connection as lost via
 *       {@link #connectionLostMarker()}.</li>
 *   <li>{@link #REDIRECT} — deliver to the console instead of the recipient.</li>
 *   <li>{@link #RATE_LIMIT} — defer/queue while the per-second budget is
 *       exhausted.</li>
 * </ul>
 */
public enum PolicyTarget {

    LOG,
    DROP,
    REJECT,
    REDIRECT,
    RATE_LIMIT;

    /** Marker written when a message is rejected, simulating a lost connection. */
    public static final String CONNECTION_LOST_MARKER = "\uFFFD";

    public boolean isConnectionLostMarker(String text) {
        return REJECT == this && text != null && text.contains(CONNECTION_LOST_MARKER);
    }
}