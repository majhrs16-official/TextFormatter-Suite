package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineServices;
import me.majhrs16.suite.api.pipeline.PipelineSettings;
import me.majhrs16.suite.api.pipeline.PipelineTrace;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.pipeline.StageTrace;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.textformatter.template.Template;
import me.majhrs16.suite.textformatter.template.TemplateContext;
import me.majhrs16.suite.textformatter.template.TemplateRenderer;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatStageTest {

    private static final Actor ALEX = new Actor(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Alex",
            Actor.ActorKind.PLAYER, Language.FR, null);

    @Test
    void rendersWithResolvedSourceAndRecipientLanguage() {
        Message message = Message.builder()
                .text("hola")
                .resolvedSourceLanguage(Language.ES)
                .build();
        PipelineMessage unit = PipelineMessage.of(message).withRecipient(ALEX);

        StageResult result = new FormatStage(new EchoFormatter())
                .perRecipient(unit, context(Language.EN));

        assertEquals(StageResult.Kind.CONTINUE, result.kind());
        assertEquals("ES->FR:hola", result.message().rendered().plainText());
    }

    @Test
    void fallsBackToSettingsWhenMessageHasNoLanguage() {
        PipelineMessage unit = PipelineMessage.of(Message.builder().text("hello").build())
                .withRecipient(ALEX);

        StageResult result = new FormatStage(new EchoFormatter())
                .perRecipient(unit, context(Language.DE));

        assertEquals("DE->FR:hello", result.message().rendered().plainText());
    }

    @Test
    void missesRecipientIsAnExplicitError() {
        StageResult result = new FormatStage(new EchoFormatter())
                .perRecipient(PipelineMessage.of(Message.builder().text("x").build()),
                        context(Language.EN));

        assertEquals(StageResult.Kind.ERROR, result.kind());
        assertEquals("format requires a recipient", result.detail());
    }

    private static PipelineContext context(Language defaultLanguage) {
        return new Context(defaultLanguage);
    }

    private static final class EchoFormatter implements TextFormatter {
        @Override public ChannelRegistry channels() { return null; }
        @Override public TemplateRenderer renderer() { return null; }
        @Override public Component format(Message message, TemplateContext context) {
            return Component.text(context.sourceLanguage() + "->"
                    + context.targetLanguage() + ":" + context.content());
        }
        @Override public Component formatTooltip(Message message, TemplateContext context) {
            return Component.empty();
        }
        @Override public Component render(Template template, TemplateContext context) {
            return Component.empty();
        }
        @Override public Channel tail(Message message, String channelPath) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Context implements PipelineContext {
        private final Language defaultLanguage;

        private Context(Language defaultLanguage) {
            this.defaultLanguage = defaultLanguage;
        }

        @Override public String messageId() { return "test"; }
        @Override public PipelineScope scope() { return PipelineScope.RECIPIENT; }
        @Override public Optional<ChannelRegistry> channels() { return Optional.empty(); }
        @Override public ActorDirectory actors() { return EmptyActors.INSTANCE; }
        @Override public PermissionChecker permissions() { return PermissionChecker.ALLOW_ALL; }
        @Override public PipelineSettings settings() {
            return new PipelineSettings(defaultLanguage, false, false);
        }
        @Override public PipelineServices services() { return EmptyServices.INSTANCE; }
        @Override public OutputPlan outputs() { return OutputPlan.inherit(); }
        @Override public PluginLogger logger() { return NoopLogger.INSTANCE; }
        @Override public PipelineTrace trace() { return EmptyTrace.INSTANCE; }
        @Override public long planVersion() { return 1L; }
    }

    private enum EmptyActors implements ActorDirectory {
        INSTANCE;

        @Override public List<Actor> onlinePlayers() { return List.of(); }
        @Override public Optional<Actor> byUuid(UUID uuid) { return Optional.empty(); }
        @Override public Optional<Actor> byName(String name) { return Optional.empty(); }
        @Override public Actor console() { return Actor.console("Server", Language.EN); }
    }

    private enum EmptyServices implements PipelineServices {
        INSTANCE;

        @Override public <T> Optional<T> find(Class<T> type) { return Optional.empty(); }
        @Override public <T> List<T> findAll(Class<T> type) { return List.of(); }
    }

    private enum EmptyTrace implements PipelineTrace {
        INSTANCE;

        @Override public void record(StageTrace entry) { }
        @Override public List<StageTrace> entries() { return List.of(); }
    }

    private enum NoopLogger implements PluginLogger {
        INSTANCE;

        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}
