package me.majhrs16.suite.api.pipeline;

/**
 * Explicit outcome of delivering one {@link DeliveryEnvelope}.
 *
 * <p>The local sink returns this summary as the value of
 * {@code SyncResult.Ok}; the engine uses it to report what actually happened
 * to each rendered unit. A failure that prevents the whole envelope from being
 * attempted is reported as {@code SyncResult.HostFailure} instead, so it is
 * never confused with a summary of zero deliveries.</p>
 *
 * @param delivered  units shown to their recipient
 * @param silenced   units discarded by a routing decision
 * @param redirected units shown on the console instead of the recipient
 * @param failed     units whose delivery raised
 */
public record DeliverySummary(
        int delivered,
        int silenced,
        int redirected,
        int failed
) {

    public DeliverySummary {
        if (delivered < 0 || silenced < 0 || redirected < 0 || failed < 0) {
            throw new IllegalArgumentException("delivery counters must not be negative");
        }
    }

    /** @return a summary with every counter at zero */
    public static DeliverySummary empty() {
        return new DeliverySummary(0, 0, 0, 0);
    }

    /** @return the number of units described by this summary */
    public int total() {
        return delivered + silenced + redirected + failed;
    }

    /** @return the sum of both summaries */
    public DeliverySummary plus(DeliverySummary other) {
        if (other == null) {
            return this;
        }
        return new DeliverySummary(
                delivered + other.delivered,
                silenced + other.silenced,
                redirected + other.redirected,
                failed + other.failed);
    }
}
