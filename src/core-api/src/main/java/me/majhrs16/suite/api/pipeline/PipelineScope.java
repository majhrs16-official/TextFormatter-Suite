package me.majhrs16.suite.api.pipeline;

/**
 * The phase in which a {@link PipelineStage} participates.
 *
 * <p>The engine walks the same ordered stage list three times:</p>
 *
 * <ol>
 *   <li>{@link #MESSAGE_PRE}: once per emission, before recipients are expanded.</li>
 *   <li>{@link #RECIPIENT}: once per recipient; {@code route}, {@code translate}
 *       and {@code format} live here.</li>
 *   <li>{@link #MESSAGE_POST}: once per emission, after delivery; {@code sync}
 *       lives here.</li>
 * </ol>
 */
public enum PipelineScope {

    /** Once per emission, before the recipient list is materialized. */
    MESSAGE_PRE,

    /** Once per recipient; the transformation phase. */
    RECIPIENT,

    /** Once per emission, after the deliveries. */
    MESSAGE_POST
}
