package me.majhrs16.suite.chattranslator;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.spi.TranslationService;

import java.util.Objects;
import java.util.Set;

/**
 * {@code translate} stage: translates the raw message texts for one recipient.
 *
 * <p>It runs in {@link PipelineScope#RECIPIENT}, after {@code route} and before
 * {@code format}. The stage transforms {@link Message#messages()} directly, so
 * the formatter renders already-translated text and no template tag is needed.
 * Formats travel untouched alongside the translated texts.</p>
 *
 * <p>Translation is skipped explicitly when the message disables it, the
 * source and target languages are equal or the target is {@link
 * Language#AUTO}, or the translation service has no usable provider. In those
 * cases the unit continues unchanged with {@code NOT_APPLICABLE}.</p>
 */
public final class TranslateStage implements PipelineStage {

    /** Contract id of the translate stage. */
    public static final String ID = "translate";

    private final TranslationService translation;

    public TranslateStage(TranslationService translation) {
        this.translation = Objects.requireNonNull(translation, "translation");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<PipelineScope> scopes() {
        return Set.of(PipelineScope.RECIPIENT);
    }

    @Override
    public StageResult perRecipient(PipelineMessage unit, PipelineContext context) {
        Actor recipient = unit.recipient();
        if (recipient == null) {
            return StageResult.error(unit, "translate requires a recipient");
        }
        Message message = unit.message();
        if (!message.shouldTranslate()) {
            return StageResult.notApplicable(unit);
        }
        Language target = targetLanguage(recipient, context);
        if (target == Language.AUTO) {
            return StageResult.notApplicable(unit);
        }
        Language source = sourceLanguage(message, context);
        if (source == target) {
            return StageResult.notApplicable(unit);
        }
        if (!translation.isAvailable()) {
            return StageResult.notApplicable(unit);
        }

        Formats messages = message.messages();
        String[] texts = messages.texts();
        if (texts.length == 0) {
            return StageResult.notApplicable(unit);
        }
        String[] translated = new String[texts.length];
        for (int i = 0; i < texts.length; i++) {
            translated[i] = translation.translate(texts[i], source, target);
        }
        Formats next = new Formats.Builder()
                .texts(translated)
                .formats(messages.formats())
                .build();
        return StageResult.continueWith(unit.withMessage(
                message.toBuilder().messages(next).build()));
    }

    private static Language targetLanguage(Actor recipient, PipelineContext context) {
        if (recipient.language() != null && recipient.language() != Language.AUTO) {
            return recipient.language();
        }
        Language fallback = context.settings().defaultLanguage();
        return fallback == null ? Language.AUTO : fallback;
    }

    private static Language sourceLanguage(Message message, PipelineContext context) {
        if (message.resolvedSourceLanguage() != null
                && message.resolvedSourceLanguage() != Language.AUTO) {
            return message.resolvedSourceLanguage();
        }
        if (message.langSource() != null && message.langSource() != Language.AUTO) {
            return message.langSource();
        }
        Language fallback = context.settings().defaultLanguage();
        return fallback == null || fallback == Language.AUTO ? Language.EN : fallback;
    }
}
