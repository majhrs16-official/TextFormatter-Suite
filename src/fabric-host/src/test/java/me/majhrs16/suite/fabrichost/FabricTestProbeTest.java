package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;

import org.junit.jupiter.api.Test;

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
        MessageDispatch dispatch = message -> { };
        try (TranslationService translation = new TranslationService(new TranslatorManager())) {
            FabricTestProbe probe = new FabricTestProbe(
                channels, router, renderer, dispatch, translation, () -> 3, Language.EN);

            assertThat(probe.ready()).isTrue();
            assertThat(probe.discoveredModules()).isEqualTo(3);
        }
    }

    @Test
    void isNotReadyWhenAPortIsMissing() {
        FabricTestProbe probe = new FabricTestProbe(
            null, null, null, null, null, null, Language.EN);

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
                return new DispatchOutcome(2, 1, 3);
            }
        };
        FabricTestProbe probe = new FabricTestProbe(
            ChannelRegistry.builder().build(), mock(Router.class),
            (message, content, recipient, language) -> content,
            dispatch, null, () -> 0, Language.EN);

        assertThat(probe.dispatch(message())).isEqualTo(new DispatchOutcome(2, 1, 3));
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
            message -> { }, null, () -> 0, Language.ES);

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
                message -> { }, translation, () -> 0, Language.EN);

            assertThat(probe.detectLanguage("Hello")).isEqualTo("en");
        }
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
}
