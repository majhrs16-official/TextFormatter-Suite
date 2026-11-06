package me.majhrs16.suite.host;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineServices;
import me.majhrs16.suite.api.pipeline.PipelineSettings;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;

import java.util.Objects;
import java.util.Optional;

/**
 * Neutral collaborators the engine shares with every dispatch.
 *
 * <p>Feature-specific services are resolved by each stage's module and passed
 * to the stage constructor; this environment only carries the cross-cutting
 * ports. {@code channels} is empty when {@code textformatter} is not active.</p>
 */
public record PipelineEnvironment(
        ActorDirectory actors,
        PermissionChecker permissions,
        Optional<ChannelRegistry> channels,
        Optional<TranslationService> translation,
        PipelineSettings settings,
        PipelineServices services,
        OutputPlan outputs,
        PluginLogger logger,
        boolean parallel
) {

    public PipelineEnvironment {
        Objects.requireNonNull(actors, "actors");
        Objects.requireNonNull(permissions, "permissions");
        channels = channels == null ? Optional.empty() : channels;
        translation = translation == null ? Optional.empty() : translation;
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(services, "services");
        outputs = outputs == null ? OutputPlan.inherit() : outputs;
        Objects.requireNonNull(logger, "logger");
    }
}
