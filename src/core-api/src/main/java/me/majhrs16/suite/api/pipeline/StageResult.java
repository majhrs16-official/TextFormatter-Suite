package me.majhrs16.suite.api.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * What a stage wants the engine to do with the current unit.
 *
 * <p>Use the factory methods; a stage never builds an inconsistent result.
 * {@link #detail()} is free-form and appears in the trace, so it must not
 * contain message content or personal data. {@link #outputs()} carries the
 * per-target results of a delivery stage and is empty for transformation
 * stages.</p>
 */
public record StageResult(Kind kind, PipelineMessage message, String detail,
                          List<OutputOutcome> outputs) {

    /** Semantic results a stage can return. */
    public enum Kind {
        /** Replace the unit with {@link StageResult#message()} and continue. */
        CONTINUE,
        /** Discard the unit without delivering it. */
        DROP,
        /** Discard the unit and report an explicit rejection. */
        REJECT,
        /** Fail the unit; it is not delivered. */
        ERROR,
        /** The stage does not apply and the unit continues unchanged. */
        NOT_APPLICABLE
    }

    public StageResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }

    public static StageResult continueWith(PipelineMessage message) {
        return new StageResult(Kind.CONTINUE, message, null, List.of());
    }

    public static StageResult continueWith(PipelineMessage message,
                                           List<OutputOutcome> outputs) {
        return new StageResult(Kind.CONTINUE, message, null, outputs);
    }

    public static StageResult notApplicable(PipelineMessage message) {
        return new StageResult(Kind.NOT_APPLICABLE, message, null, List.of());
    }

    public static StageResult notApplicable(PipelineMessage message,
                                            List<OutputOutcome> outputs) {
        return new StageResult(Kind.NOT_APPLICABLE, message, null, outputs);
    }

    public static StageResult drop(PipelineMessage message, String detail) {
        return new StageResult(Kind.DROP, message, detail, List.of());
    }

    public static StageResult reject(PipelineMessage message, String detail) {
        return new StageResult(Kind.REJECT, message, detail, List.of());
    }

    public static StageResult error(PipelineMessage message, String detail) {
        return new StageResult(Kind.ERROR, message, detail, List.of());
    }

    /**
     * @param outputs accumulated per-target results
     * @return the same result with the given outputs
     */
    public StageResult withOutputs(List<OutputOutcome> outputs) {
        return new StageResult(kind, message, detail, outputs);
    }

    /** @return whether the engine must stop processing this unit */
    public boolean terminal() {
        return kind != Kind.CONTINUE && kind != Kind.NOT_APPLICABLE;
    }

    /** @return the trace status this result maps to */
    public StageStatus traceStatus() {
        return switch (kind) {
            case CONTINUE -> StageStatus.RUN;
            case DROP -> StageStatus.DROP;
            case REJECT -> StageStatus.REJECT;
            case ERROR -> StageStatus.ERROR;
            case NOT_APPLICABLE -> StageStatus.NOT_APPLICABLE;
        };
    }
}
