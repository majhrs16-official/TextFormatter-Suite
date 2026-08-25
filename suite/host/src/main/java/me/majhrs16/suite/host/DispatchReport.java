package me.majhrs16.suite.host;

/**
 * Outcome of one {@link MessageDispatcher#dispatch} pass.
 *
 * @param considered how many recipients the direction expanded to.
 * @param delivered  recipients that received the rendered message.
 * @param silenced   recipients dropped/rejected/rate-limited by routing.
 * @param redirected copies sent to the console instead of a recipient.
 * @param skipReason why nothing was considered at all ({@code null} when the
 *                   pass ran; e.g. {@code "cancelled"}).
 */
public record DispatchReport(int considered, int delivered, int silenced,
                             int redirected, String skipReason) {

    /** Report for a pass that never ran (e.g. {@code "cancelled"} message). */
    public static DispatchReport none(String reason) {
        return new DispatchReport(0, 0, 0, 0, reason);
    }

    public boolean anythingSent() {
        return delivered > 0 || redirected > 0;
    }
}
