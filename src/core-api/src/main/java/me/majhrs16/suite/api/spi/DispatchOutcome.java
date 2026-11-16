package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.pipeline.OutputOutcome;
import me.majhrs16.suite.api.pipeline.PipelineStatus;

import java.util.List;
import java.util.Objects;

/**
 * Neutral counters and status for one dispatch pass.
 *
 * <p>Platform adapters use this record instead of the host's concrete
 * dispatch report, so diagnostic and test code can observe delivery without
 * importing host types. {@link #outputs()} keeps the per-target results in
 * output-plan order and exposes partial failures instead of hiding them in a
 * total-success counter.</p>
 *
 * @param status      resolved pipeline status for this dispatch
 * @param considered  recipients the direction expanded to
 * @param delivered   recipients that received the rendered message
 * @param silenced    recipients dropped/rejected/rate-limited by a stage
 * @param redirected  copies sent to the console instead of a recipient
 * @param failed      recipients whose stage or delivery failed
 * @param queued      rate-limited units accepted for later delivery
 * @param discarded   rate-limited units dropped because the bounded queue was full
 * @param outputs     per-target delivery results, in output-plan order
 */
public record DispatchOutcome(
        PipelineStatus status,
        int considered,
        int delivered,
        int silenced,
        int redirected,
        int failed,
        int queued,
        int discarded,
        List<OutputOutcome> outputs
) {

    public DispatchOutcome {
        Objects.requireNonNull(status, "status");
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }

    /** Outcome for a pass that never entered a pipeline or has no report. */
    public static DispatchOutcome none() {
        return new DispatchOutcome(PipelineStatus.NO_PIPELINE, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    /** @return whether anything reached a destination */
    public boolean anythingSent() {
        return delivered > 0 || redirected > 0;
    }

    /** @return whether at least one selected output failed */
    public boolean hasOutputFailure() {
        return outputs.stream().anyMatch(outcome -> !outcome.isOk());
    }
}
