package me.majhrs16.suite.chattranslator;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineSettings;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranslateStageTest {

    private static final Actor STEVE = new Actor(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve",
            Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor ALEX = new Actor(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Alex",
            Actor.ActorKind.PLAYER, Language.ES, null);

    private static final Translator PREFIXING = new Translator() {
        @Override public String name() { return "fake"; }
        @Override public String translate(String text, String from, String to) {
            return to + ":" + text;
        }
        @Override public String detect(String text) { return "en"; }
        @Override public boolean isAvailable() { return true; }
    };

    @Test
    void translatesEveryTextAndKeepsItsFormat() {
        Message message = message(new Formats(
                new String[]{"hello", "world"},
                new String[]{"<gray>%content%</gray>", "<red>%content%</red>"}));

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message).withRecipient(ALEX), context(Language.EN));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.CONTINUE);
            assertThat(result.message().message().texts()).containsExactly("es:hello", "es:world");
            assertThat(result.message().message().messages().formats())
                    .containsExactly("<gray>%content%</gray>", "<red>%content%</red>");
        }
    }

    @Test
    void sameLanguageIsNotApplicable() {
        Message message = message(new Formats(
                new String[]{"hola"}, new String[0]));

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message.withResolvedSourceLanguage(Language.ES))
                            .withRecipient(ALEX),
                    context(Language.EN));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.NOT_APPLICABLE);
            assertThat(result.message().message().texts()).containsExactly("hola");
        }
    }

    @Test
    void autoTargetIsNotApplicable() {
        Message message = message(new Formats(new String[]{"hello"}, new String[0]));
        Actor noLanguage = new Actor(UUID.randomUUID(), "Nobody",
                Actor.ActorKind.UNKNOWN, Language.AUTO, null);

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message).withRecipient(noLanguage), context(Language.AUTO));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.NOT_APPLICABLE);
            assertThat(result.message().message().texts()).containsExactly("hello");
        }
    }

    @Test
    void disabledTranslationIsNotApplicable() {
        Message message = message(new Formats(new String[]{"hello"}, new String[0]))
                .withTranslate(false);

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message).withRecipient(ALEX), context(Language.EN));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.NOT_APPLICABLE);
            assertThat(result.message().message().texts()).containsExactly("hello");
        }
    }

    @Test
    void unavailableServiceIsNotApplicable() {
        Message message = message(new Formats(new String[]{"hello"}, new String[0]));

        try (TranslationService translation = service(null)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message).withRecipient(ALEX), context(Language.EN));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.NOT_APPLICABLE);
            assertThat(result.message().message().texts()).containsExactly("hello");
        }
    }

    @Test
    void emptyTextsAreNotApplicable() {
        Message message = message(Formats.empty());

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message).withRecipient(ALEX), context(Language.EN));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.NOT_APPLICABLE);
        }
    }

    @Test
    void fallsBackToSettingsForSourceLanguage() {
        Message message = message(new Formats(new String[]{"hello"}, new String[0]));

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message).withRecipient(ALEX), context(Language.EN));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.CONTINUE);
            assertThat(result.message().message().texts()).containsExactly("es:hello");
        }
    }

    @Test
    void missingRecipientIsAnExplicitError() {
        Message message = message(new Formats(new String[]{"hello"}, new String[0]));

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message), context(Language.EN));

            assertThat(result.kind()).isEqualTo(StageResult.Kind.ERROR);
            assertThat(result.detail()).isEqualTo("translate requires a recipient");
        }
    }

    @Test
    void translationOutputIsNotMiniMessageEscaped() {
        Translator markup = new Translator() {
            @Override public String name() { return "markup"; }
            @Override public String translate(String text, String from, String to) {
                return "<b>" + text + "</b>";
            }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        };

        try (TranslationService translation = service(markup)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message(new Formats(new String[]{"hi"}, new String[0])))
                            .withRecipient(ALEX),
                    context(Language.EN));

            assertThat(result.message().message().texts()).containsExactly("<b>hi</b>");
        }
    }

    @Test
    void doesNotMutateTheOriginalMessage() {
        Message message = message(new Formats(new String[]{"hello"}, new String[0]));

        try (TranslationService translation = service(PREFIXING)) {
            StageResult result = stage(translation).perRecipient(
                    PipelineMessage.of(message).withRecipient(ALEX), context(Language.EN));

            assertThat(result.message().message()).isNotSameAs(message);
            assertThat(message.texts()).containsExactly("hello");
        }
    }

    private static TranslateStage stage(TranslationService translation) {
        return new TranslateStage(translation);
    }

    private static Message message(Formats messages) {
        return Message.builder()
                .sender(STEVE)
                .messages(messages)
                .resolvedSourceLanguage(Language.EN)
                .build();
    }

    private static TranslationService service(Translator translator) {
        TranslatorManager manager = new TranslatorManager();
        if (translator != null) {
            manager.add(translator);
        }
        return new TranslationService(manager);
    }

    private static PipelineContext context(Language defaultLanguage) {
        PipelineContext context = mock(PipelineContext.class);
        when(context.settings())
                .thenReturn(new PipelineSettings(defaultLanguage, false, false));
        return context;
    }
}
