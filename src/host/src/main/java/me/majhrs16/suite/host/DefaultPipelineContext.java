package me.majhrs16.suite.host;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineServices;
import me.majhrs16.suite.api.pipeline.PipelineSettings;
import me.majhrs16.suite.api.pipeline.PipelineTrace;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable-per-phase view of {@link PipelineEnvironment}.
 *
 * <p>A new instance is created for each phase/recipient, so parallel
 * recipients never share a mutable scope. Dependencies are stable for the
 * lifetime of the plan.</p>
 */
final class DefaultPipelineContext implements PipelineContext {

    private final String messageId;
    private final PipelineScope scope;
    private final PipelineEnvironment environment;
    private final PipelineTrace trace;
    private final long planVersion;

    DefaultPipelineContext(String messageId,
                           PipelineScope scope,
                           PipelineEnvironment environment,
                           PipelineTrace trace,
                           long planVersion) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.trace = Objects.requireNonNull(trace, "trace");
        this.planVersion = planVersion;
    }

    @Override public String messageId() { return messageId; }
    @Override public PipelineScope scope() { return scope; }
    @Override public Optional<ChannelRegistry> channels() { return environment.channels(); }
    @Override public ActorDirectory actors() { return environment.actors(); }
    @Override public PermissionChecker permissions() { return environment.permissions(); }
    @Override public PipelineSettings settings() { return environment.settings(); }
    @Override public PipelineServices services() { return environment.services(); }
    @Override public OutputPlan outputs() { return environment.outputs(); }
    @Override public PluginLogger logger() { return environment.logger(); }
    @Override public PipelineTrace trace() { return trace; }
    @Override public long planVersion() { return planVersion; }
}
