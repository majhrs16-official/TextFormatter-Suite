package me.majhrs16.suite.api.pipeline;

import java.util.Set;

/**
 * One transform in the ordered pipeline.
 *
 * <p>A stage declares the phases it participates in and leaves the others at
 * their pass-through default. The engine invokes only the declared methods, so
 * a stage can implement a single phase without boilerplate.</p>
 *
 * <p>Implementations are registered as services by their own module; the
 * engine discovers them through the runtime service registry. Two modules may
 * not publish the same {@link #id()}: that is an invalid plan.</p>
 */
public interface PipelineStage {

    /**
     * Stable contract id, matching {@code ^[a-z][a-z0-9-]*$}. Base ids are
     * {@code route}, {@code translate}, {@code format} and {@code sync}.
     */
    String id();

    /** @return phases in which the engine must invoke this stage */
    Set<PipelineScope> scopes();

    /** Runs once per emission, before recipients are expanded. */
    default StageResult beforeMessage(PipelineMessage message, PipelineContext context) {
        return StageResult.continueWith(message);
    }

    /** Runs once per recipient. */
    default StageResult perRecipient(PipelineMessage message, PipelineContext context) {
        return StageResult.continueWith(message);
    }

    /** Runs once per emission, after delivery. */
    default StageResult afterMessage(PipelineMessage message, PipelineContext context) {
        return StageResult.continueWith(message);
    }
}
