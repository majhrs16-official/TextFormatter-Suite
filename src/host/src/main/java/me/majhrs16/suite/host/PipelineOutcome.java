package me.majhrs16.suite.host;

import me.majhrs16.suite.api.pipeline.PipelineStatus;
import me.majhrs16.suite.api.pipeline.PipelineTrace;

import java.util.Objects;

/**
 * Result of one engine pass.
 *
 * @param status    resolved pipeline status for this dispatch
 * @param considered recipients the direction expanded to
 * @param delivered  recipients that received the rendered message
 * @param silenced   recipients discarded/rejected/throttled by a stage
 * @param redirected console copies produced by a redirect decision
 * @param failed     recipients whose stage or delivery failed
 * @param cancelEvent whether a stage requested cancelling the platform event
 * @param trace      per-stage trace for diagnostics
 * @param skipReason why nothing ran ({@code null} when the pass ran)
 */
public record PipelineOutcome(
        PipelineStatus status,
        int considered,
        int delivered,
        int silenced,
        int redirected,
        int failed,
        boolean cancelEvent,
        PipelineTrace trace,
        String skipReason
) {

    public PipelineOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(trace, "trace");
    }

    /** Outcome for a message that never entered the pipeline. */
    public static PipelineOutcome none(PipelineStatus status, PipelineTrace trace, String reason) {
        return new PipelineOutcome(status, 0, 0, 0, 0, 0, false, trace, reason);
    }

    /** @return whether anything reached a destination */
    public boolean anythingSent() {
        return delivered > 0 || redirected > 0;
    }
}
