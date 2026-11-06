package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.render.ComponentRenderedOutput;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Objects;
import java.util.Set;

/**
 * {@code format} stage: turns the routed (and eventually translated) message
 * into a rendered component for one recipient.
 *
 * <p>The heavy lifting stays in {@link TextFormatter}; this stage only adapts
 * it to the pipeline. The engine already resolved the source language once per
 * emission, so the stage reads it from the message and falls back to the
 * neutral settings.</p>
 */
public final class FormatStage implements PipelineStage {

    /** Contract id of the format stage. */
    public static final String ID = "format";

    private final TextFormatterMessageRenderer renderer;

    public FormatStage(TextFormatter formatter) {
        this.renderer = new TextFormatterMessageRenderer(
                Objects.requireNonNull(formatter, "formatter"));
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
            return StageResult.error(unit, "format requires a recipient");
        }
        Message message = unit.message();
        Language fallback = context.settings().defaultLanguage();
        Language source = sourceLanguage(message, fallback);
        Language target = recipient.language() != null && recipient.language() != Language.AUTO
                ? recipient.language()
                : fallback;
        Component rendered = renderer.render(message, recipient, source, target);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        return StageResult.continueWith(
                unit.withRendered(new ComponentRenderedOutput(rendered, plain)));
    }

    private static Language sourceLanguage(Message message, Language fallback) {
        if (message.resolvedSourceLanguage() != null
                && message.resolvedSourceLanguage() != Language.AUTO) {
            return message.resolvedSourceLanguage();
        }
        if (message.langSource() != null && message.langSource() != Language.AUTO) {
            return message.langSource();
        }
        return fallback == null || fallback == Language.AUTO ? Language.EN : fallback;
    }
}
