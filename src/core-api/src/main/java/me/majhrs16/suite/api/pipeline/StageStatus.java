package me.majhrs16.suite.api.pipeline;

/**
 * Outcome of one stage/fase execution.
 *
 * <p>It is the status recorded in a {@link StageTrace}; {@link StageResult}
 * carries the semantic result and this enum is its observable trace form.</p>
 */
public enum StageStatus {

    /** The stage ran and returned a message. */
    RUN,

    /** The stage is listed but was not available; the pipeline continued. */
    SKIPPED,

    /** The unit was discarded. */
    DROP,

    /** The unit was explicitly rejected. */
    REJECT,

    /** The stage failed; the unit is not delivered. */
    ERROR,

    /** The stage did not apply to this unit and returned it unchanged. */
    NOT_APPLICABLE
}
