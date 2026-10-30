package me.majhrs16.suite.api.spi;

/**
 * Neutral counters for one dispatch pass.
 *
 * <p>Platform adapters use this record instead of the host's concrete
 * {@code DispatchReport}, so diagnostic and test code can observe delivery
 * without importing host types.</p>
 *
 * @param delivered recipients that received the rendered message
 * @param silenced  recipients dropped/rejected/rate-limited by routing
 * @param redirected copies sent to the console instead of a recipient
 */
public record DispatchOutcome(int delivered, int silenced, int redirected) {

    /** Outcome for a pass that never ran or has no report. */
    public static DispatchOutcome none() {
        return new DispatchOutcome(0, 0, 0);
    }
}
