package me.majhrs16.suite.api.pipeline;

/**
 * State of the resolved pipeline for a dispatch.
 *
 * <ul>
 *   <li>{@link #READY}: a valid plan with at least one effective stage.</li>
 *   <li>{@link #NO_PIPELINE}: a valid plan with zero effective stages; nothing
 *       is delivered and the outcome says so instead of faking success.</li>
 *   <li>{@link #INVALID}: the configuration could not produce a plan.</li>
 * </ul>
 */
public enum PipelineStatus {

    /** The plan is valid and has at least one effective stage. */
    READY,

    /** The plan is valid but no stage is effective; nothing is delivered. */
    NO_PIPELINE,

    /** The plan could not be built because the configuration is invalid. */
    INVALID
}
