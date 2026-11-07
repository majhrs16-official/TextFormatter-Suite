package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.pipeline.PipelineStatus;

import java.util.Objects;

/**
 * Neutral counters and status for one dispatch pass.
 *
 * <p>Platform adapters use this record instead of the host's concrete
 * dispatch report, so diagnostic and test code can observe delivery without
 * importing host types.</p>
 *
 * @param status      resolved pipeline status for this dispatch
 * @param considered  recipients the direction expanded to
 * @param delivered   recipients that received the rendered message
 * @param silenced    recipients dropped/rejected/rate-limited by a stage
 * @param redirected  copies sent to the console instead of a recipient
 * @param failed      recipients whose stage or delivery failed
 */
public record DispatchOutcome(
        PipelineStatus status,
        int considered,
        int delivered,
        int silenced,
        int redirected,
        int failed
) {

    public DispatchOutcome {
        Objects.requireNonNull(status, "status");
    }

    /** Outcome for a pass that never entered a pipeline or has no report. */
    public static DispatchOutcome none() {
        return new DispatchOutcome(PipelineStatus.NO_PIPELINE, 0, 0, 0, 0, 0);
    }

    /** @return whether anything reached a destination */
    public boolean anythingSent() {
        return delivered > 0 || redirected > 0;
    }
}
