package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.render.ComponentRenderedOutput;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
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
 *
 * <p>When the effective {@link OutputPlan} declares a
 * {@code chattranslator.<backend>} target, the {@code <tr>} spans of this
 * rendering use that provider; without a target the translation facade keeps
 * its default backend and fallback. More than one target is a conflict, and an
 * unknown provider is an explicit error.</p>
 */
public final class FormatStage implements PipelineStage {

    /** Contract id of the format stage. */
    public static final String ID = "format";

    private static final String MULTIPLEXER = "chattranslator";

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
        List<String> backends = translationBackends(context.outputs().targets());
        if (backends.size() > 1) {
            return StageResult.error(unit, "CONFLICT: multiple 'chattranslator' output targets");
        }
        String backend = backends.isEmpty() ? null : backends.get(0);
        if (backend != null && !renderer.hasTranslationBackend(backend)) {
            return StageResult.error(unit, "unknown chattranslator backend '" + backend + "'");
        }
        Component rendered = renderer.render(message, recipient, source, target, backend);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        return StageResult.continueWith(
                unit.withRendered(new ComponentRenderedOutput(rendered, plain)));
    }

    /** @return backend names named by {@code chattranslator.<backend>} targets, in order */
    static List<String> translationBackends(List<String> targets) {
        return targets.stream()
                .filter(target -> target.startsWith(MULTIPLEXER + "."))
                .map(target -> target.substring(MULTIPLEXER.length() + 1))
                .filter(backend -> !backend.isBlank())
                .toList();
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
