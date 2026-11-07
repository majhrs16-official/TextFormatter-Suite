package me.majhrs16.suite.host;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.RateLimiter;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelinePlan;
import me.majhrs16.suite.api.pipeline.PipelineServices;
import me.majhrs16.suite.api.pipeline.PipelineSettings;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.chattranslator.BackendConfig;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.hub.RouteStage;
import me.majhrs16.suite.iflow.vm.VmConfigLoader;
import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.textformatter.ChannelConfig;
import me.majhrs16.suite.textformatter.FormatStage;
import me.majhrs16.suite.textformatter.TextFormatter;
import me.majhrs16.suite.textformatter.TextFormatters;

import net.kyori.adventure.text.Component;

/**
 * Test-only pipeline wiring for the host integration tests.
 *
 * <p>The production runtime resolves stages from installed modules. Tests still
 * need a compact pipeline built from the real feature implementations, so the
 * composition lives here instead of in {@code src/main}.</p>
 */
final class HostFixture {

    private HostFixture() {
    }

    /** Built pipeline plus the collaborators a test may need to inspect. */
    static final class Pipeline implements AutoCloseable {

        private final PipelineEngine engine;
        private final DeliveryExecutor executor;
        private final Router router;
        private final ChannelRegistry channels;
        private final TranslationService translation;
        private final HostConfig config;
        private final ChatDelivery delivery;
        private final ActorDirectory actors;

        private Pipeline(PipelineEngine engine, DeliveryExecutor executor, Router router,
                         ChannelRegistry channels, TranslationService translation,
                         HostConfig config, ChatDelivery delivery, ActorDirectory actors) {
            this.engine = engine;
            this.executor = executor;
            this.router = router;
            this.channels = channels;
            this.translation = translation;
            this.config = config;
            this.delivery = delivery;
            this.actors = actors;
        }

        PipelineEngine engine() {
            return engine;
        }

        Router router() {
            return router;
        }

        ChannelRegistry channels() {
            return channels;
        }

        TranslationService translation() {
            return translation;
        }

        HostConfig config() {
            return config;
        }

        ChatDelivery delivery() {
            return delivery;
        }

        ActorDirectory actors() {
            return actors;
        }

        PipelineOutcome dispatch(Message message) {
            return engine.dispatch(message);
        }

        @Override
        public void close() {
            try {
                engine.close();
            } finally {
                executor.close();
                translation.close();
            }
        }
    }

    static Pipeline bootstrap(Path configDir, PermissionChecker permissions,
                              PluginLogger logger) {
        return bootstrap(configDir, permissions, null, logger, null, null);
    }

    static Pipeline bootstrap(Path configDir, PermissionChecker permissions,
                              PlaceholderResolver placeholders, PluginLogger logger) {
        return bootstrap(configDir, permissions, placeholders, logger, null, null);
    }

    static Pipeline bootstrap(Path configDir, PermissionChecker permissions,
                              PlaceholderResolver placeholders, PluginLogger logger,
                              ChatDelivery delivery) {
        return bootstrap(configDir, permissions, placeholders, logger, delivery, null);
    }

    static Pipeline bootstrap(Path configDir, PermissionChecker permissions,
                              PlaceholderResolver placeholders, PluginLogger logger,
                              ChatDelivery delivery, ActorDirectory actors) {
        ConfigLoader.LoadResult<HostConfig> configResult =
                ConfigLoader.loadConfig(configDir, logger);
        if (!configResult.isValid() && logger != null) {
            configResult.errors().forEach(error -> logger.error("Config error: " + error));
        }
        HostConfig config = configResult.config();

        ChannelConfig.LoadResult<ChannelRegistry> channelsResult =
                ChannelConfig.load(configDir);
        if (!channelsResult.isValid() && logger != null) {
            channelsResult.errors().forEach(error -> logger.error("Channel config error: " + error));
        }
        ChannelRegistry channels = channelsResult.config();

        TranslatorManager translators = BackendConfig.load(configDir);
        TranslationService translation = new TranslationService(translators);
        Router router = router(configDir, channels, permissions, placeholders, translation, logger);

        TextFormatter formatter = TextFormatters.create(channels, translation, placeholders, logger);
        List<PipelineStage> published = List.of(new RouteStage(router), new FormatStage(formatter));

        PipelinePlan plan = PipelinePlan.of(config.pipeline(), 1L);
        List<ResolvedStage> resolved = plan.stageIds().stream()
                .filter(id -> published.stream().anyMatch(stage -> stage.id().equals(id)))
                .map(id -> new ResolvedStage(id, ownerOf(id), stageFor(published, id)))
                .toList();
        List<SkippedStage> skipped = plan.stageIds().stream()
                .filter(id -> published.stream().noneMatch(stage -> stage.id().equals(id)))
                .map(id -> new SkippedStage(id, "stage-unpublished"))
                .toList();

        ChatDelivery effectiveDelivery = delivery == null ? NoopDelivery.INSTANCE : delivery;
        ActorDirectory effectiveActors = actors == null ? EmptyDirectory.INSTANCE : actors;

        PipelineEnvironment environment = new PipelineEnvironment(
                effectiveActors,
                permissions,
                Optional.of(channels),
                Optional.of(translation),
                new PipelineSettings(config.defaultLanguage(),
                        config.engineParallel(), config.soundEnabled()),
                EmptyServices.INSTANCE,
                OutputPlan.inherit(),
                logger,
                config.engineParallel());
        RecipientResolver recipients = new RecipientResolver(effectiveActors, permissions, logger);
        DeliveryExecutor executor = new DeliveryExecutor(
                effectiveDelivery, channels, config.soundEnabled(), logger);
        PipelineEngine engine = new PipelineEngine(
                plan, resolved, skipped, recipients, executor, environment, 1L);
        return new Pipeline(engine, executor, router, channels, translation,
                config, effectiveDelivery, effectiveActors);
    }

    private static String ownerOf(String stageId) {
        return switch (stageId) {
            case "route" -> "iflow";
            case "format" -> "textformatter";
            default -> "test";
        };
    }

    private static PipelineStage stageFor(List<PipelineStage> stages, String id) {
        return stages.stream()
                .filter(stage -> stage.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static Router router(Path configDir, ChannelRegistry channels,
                                 PermissionChecker permissions, PlaceholderResolver placeholders,
                                 TranslationService translation, PluginLogger logger) {
        ProgramImage program = VmConfigLoader.loadProgram(configDir, logger);
        VmPolicyEngine vm = new VmPolicyEngine(channels, permissions, new RateLimiter());
        vm.setProgram(program);
        return vm;
    }

    private static final class EmptyServices implements PipelineServices {
        static final EmptyServices INSTANCE = new EmptyServices();

        @Override
        public <T> Optional<T> find(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T> List<T> findAll(Class<T> type) {
            return List.of();
        }
    }

    private static final class EmptyDirectory implements ActorDirectory {
        static final EmptyDirectory INSTANCE = new EmptyDirectory();

        @Override public List<Actor> onlinePlayers() { return List.of(); }
        @Override public Optional<Actor> byUuid(UUID uuid) { return Optional.empty(); }
        @Override public Optional<Actor> byName(String name) { return Optional.empty(); }
        @Override public Actor console() { return Actor.console("Server", null); }
    }

    /** Delivery double that accepts everything and records nothing. */
    static final class NoopDelivery implements ChatDelivery {
        static final NoopDelivery INSTANCE = new NoopDelivery();

        @Override public void deliver(Actor recipient, Component rendered, Message original) { }
        @Override public void deliverConsole(Component rendered) { }
        @Override public void playSound(Actor recipient, SoundSpec sound) { }
        @Override public boolean hasSound(String soundName) { return false; }
    }
}
