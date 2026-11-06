package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.message.Language;

/**
 * Neutral subset of the host configuration that a stage may read.
 *
 * <p>{@code HostConfig} stays a host type and never crosses the pipeline
 * boundary.</p>
 */
public record PipelineSettings(
        Language defaultLanguage,
        boolean engineParallel,
        boolean soundEnabled
) {
}
