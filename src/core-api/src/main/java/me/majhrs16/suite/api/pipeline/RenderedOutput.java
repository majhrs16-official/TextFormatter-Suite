package me.majhrs16.suite.api.pipeline;

/**
 * Neutral marker for a rendered message.
 *
 * <p>{@code core-api} does not depend on Adventure, so the rendered output
 * cannot be a {@code Component} here. {@code core-render} provides
 * {@code ComponentRenderedOutput}, which wraps the Adventure component and is
 * the only place that knows the renderer's type.</p>
 */
public interface RenderedOutput {

    /**
     * Neutral, human-readable text of the rendered output.
     *
     * <p>Stages that only need the visible text (for example {@code sync})
     * must not cast to a platform type. Formatting stages that need the rich
     * component do the cast through {@code core-render}.</p>
     *
     * @return plain text; never {@code null}
     */
    String plainText();
}
