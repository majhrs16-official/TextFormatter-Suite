package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.util.Optional;

/**
 * Neutral services and metadata available to every {@link PipelineStage}.
 *
 * <p>Stages receive their feature-specific collaborators (formatter,
 * translator, router, bus) through their own constructor. This context only
 * exposes cross-cutting neutral services, so a missing feature never prevents
 * the engine from running the remaining stages.</p>
 */
public interface PipelineContext {

    /** @return id of the message being dispatched */
    String messageId();

    /** @return phase being executed */
    PipelineScope scope();

    /**
     * Catalog published by {@code textformatter}, or empty when that feature
     * is not active. A stage that needs it must fail only its own operation.
     */
    Optional<ChannelRegistry> channels();

    /** @return neutral actor lookup */
    ActorDirectory actors();

    /** @return neutral permission check */
    PermissionChecker permissions();

    /** @return neutral subset of the host configuration */
    PipelineSettings settings();

    /** @return read-only service lookup */
    PipelineServices services();

    /** @return effective output plan for this emission */
    OutputPlan outputs();

    /** @return host logger */
    PluginLogger logger();

    /** @return trace sink for the current dispatch */
    PipelineTrace trace();

    /** @return monotonic version of the plan being executed */
    long planVersion();
}
