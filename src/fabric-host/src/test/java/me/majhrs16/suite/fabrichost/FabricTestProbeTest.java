package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.pipeline.PipelineStatus;
import me.majhrs16.suite.api.pipeline.PipelineTrace;
import me.majhrs16.suite.api.pipeline.StageTrace;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.api.spi.PipelineTraceSource;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FabricTestProbeTest {

    @Test
    void isReadyOnlyWhenEveryPortIsPresent() {
        ChannelRegistry channels = ChannelRegistry.builder().build();
        Router router = mock(Router.class);
        MessageRenderer renderer = (message, content, recipient, language) -> content;
        MessageDispatch dispatch = new QuietDispatch();
        try (TranslationService translation = new TranslationService(new TranslatorManager())) {
            FabricTestProbe probe = new FabricTestProbe(
                channels, router, renderer, dispatch, new EmptyTraces(), translation,
                () -> 3, Language.EN);

            assertThat(probe.ready()).isTrue();
            assertThat(probe.discoveredModules()).isEqualTo(3);
        }
    }

    @Test
    void isNotReadyWhenAPortIsMissing() {
        FabricTestProbe probe = new FabricTestProbe(
            null, null, null, null, null, null, null, Language.EN);

        assertThat(probe.ready()).isFalse();
    }

    @Test
    void delegatesDispatchAndReturnsTheNeutralOutcome() {
        MessageDispatch dispatch = new MessageDispatch() {
            @Override
            public void dispatch(Message message) {
                throw new AssertionError("dispatchAndReport must be used");
            }

            @Override
            public DispatchOutcome dispatchAndReport(Message message) {
                return new DispatchOutcome(PipelineStatus.READY, 3, 2, 1, 0, 0);
            }
        };
        FabricTestProbe probe = new FabricTestProbe(
            ChannelRegistry.builder().build(), mock(Router.class),
            (message, content, recipient, language) -> content,
            dispatch, new EmptyTraces(), null, () -> 0, Language.EN);

        assertThat(probe.dispatch(message()))
            .isEqualTo(new DispatchOutcome(PipelineStatus.READY, 3, 2, 1, 0, 0));
    }

    @Test
    void formatsThroughTheNeutralRenderer() {
        AtomicReference<String> seenContent = new AtomicReference<>();
        AtomicReference<Language> seenLanguage = new AtomicReference<>();
        MessageRenderer renderer = (message, content, recipient, language) -> {
            seenContent.set(content);
            seenLanguage.set(language);
            return "rendered";
        };
        FabricTestProbe probe = new FabricTestProbe(
            ChannelRegistry.builder().build(), mock(Router.class), renderer,
            new QuietDispatch(), new EmptyTraces(), null, () -> 0, Language.ES);

        probe.format(message());

        assertThat(seenContent.get()).isEqualTo("Hello");
        assertThat(seenLanguage.get()).isEqualTo(Language.ES);
    }

    @Test
    void detectsLanguageThroughTheNeutralTranslator() {
        try (TranslationService translation = new TranslationService(new TranslatorManager())) {
            FabricTestProbe probe = new FabricTestProbe(
                ChannelRegistry.builder().build(), mock(Router.class),
                (message, content, recipient, language) -> content,
                new QuietDispatch(), new EmptyTraces(), translation, () -> 0, Language.EN);

            assertThat(probe.detectLanguage("Hello")).isEqualTo("en");
        }
    }

    @Test
    void exposesTheTraceSourceWithoutCopying() {
        PipelineTrace trace = new SingleTrace();
        PipelineTraceSource traces = new PipelineTraceSource() {
            @Override public PipelineTrace lastTrace(String messageId) { return trace; }
            @Override public List<PipelineTrace> recentTraces() { return List.of(trace); }
        };
        FabricTestProbe probe = new FabricTestProbe(
            ChannelRegistry.builder().build(), mock(Router.class),
            (message, content, recipient, language) -> content,
            new QuietDispatch(), traces, null, () -> 0, Language.EN);

        assertThat(probe.lastTrace("any")).isSameAs(trace);
        assertThat(probe.recentTraces()).containsExactly(trace);
    }

    private static Message message() {
        Actor actor = new Actor(UUID.randomUUID(), "Steve",
            Actor.ActorKind.PLAYER, Language.EN, null);
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(actor)
            .text("Hello")
            .channel("chat")
            .build();
    }

    private static final class QuietDispatch implements MessageDispatch {
        @Override public void dispatch(Message message) { }
        @Override public DispatchOutcome dispatchAndReport(Message message) {
            return DispatchOutcome.none();
        }
    }

    private static final class EmptyTraces implements PipelineTraceSource {
        @Override public PipelineTrace lastTrace(String messageId) { return null; }
        @Override public List<PipelineTrace> recentTraces() { return List.of(); }
    }

    private static final class SingleTrace implements PipelineTrace {
        @Override public void record(StageTrace entry) { }
        @Override public List<StageTrace> entries() { return List.of(); }
    }
}
