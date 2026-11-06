package me.majhrs16.suite.api.pipeline;

/**
 * One executed or skipped stage in a dispatch trace.
 *
 * <p>The trace never stores message content or personal data; {@code detail}
 * is a reason code or a short technical message.</p>
 *
 * @param stageId   stable contract id, e.g. {@code format}
 * @param moduleId  module that published the stage, or {@code null} when absent
 * @param scope     phase in which the entry was produced
 * @param recipient recipient of the unit, {@code null} outside {@code RECIPIENT}
 * @param status    observed status
 * @param detail    reason or short error
 * @param durationNanos elapsed time of the invocation
 */
public record StageTrace(
        String stageId,
        String moduleId,
        PipelineScope scope,
        me.majhrs16.suite.api.message.Actor recipient,
        StageStatus status,
        String detail,
        long durationNanos
) {
}
