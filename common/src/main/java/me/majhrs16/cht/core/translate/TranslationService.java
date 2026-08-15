package me.majhrs16.cht.core.translate;

import me.majhrs16.cht.core.language.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Facade the rest of the engine uses to interact with translation providers.
 *
 * <p>Wraps a {@link TranslatorManager} and adds batch helpers plus automatic
 * language detection. All methods are synchronous and expected to be called
 * from an asynchronous scheduler thread.</p>
 */
public final class TranslationService {

    private final TranslatorManager manager;

    public TranslationService(TranslatorManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    /**
     * Translates a single fragment.
     *
     * @param text the source text.
     * @param from source language. {@link Language#AUTO} triggers detection on
     *             the active provider.
     * @param to   target language, never {@code AUTO}.
     * @return the translated fragment; input text when {@code from == to},
     *         translation disabled, or the provider is unavailable.
     */
    public String translate(String text, Language from, Language to) {
        if (!shouldTranslate(from, to) || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String source = localization(from, text);
        try {
            return manager.active().translate(text, source, to.code());
        } catch (TranslationException e) {
            return text;
        }
    }

    /**
     * Translates a list of fragments, preserving order and skipping empties.
     */
    public List<String> translateAll(List<String> texts, Language from, Language to) {
        List<String> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(translate(text, from, to));
        }
        return result;
    }

    /**
     * Detects the language of a sample text.
     *
     * @param text the sample.
     * @return a detected language, or {@link Language#EN} when undetectable.
     */
    public Language detect(String text) {
        if (text == null || text.isEmpty()) {
            return Language.EN;
        }
        return Language.fromCode(manager.active().detect(text)).orElse(Language.EN);
    }

    /** Alias of {@link #detect(String)} returning AUTO only when undetectable. */
    public Language detectLanguage(String text) {
        return detect(text);
    }

    /**
     * @return whether any provider is currently usable.
     */
    public boolean isAvailable() {
        return manager.active().isAvailable();
    }

    private boolean shouldTranslate(Language from, Language to) {
        if (from == null || to == null || to == Language.AUTO) {
            return false;
        }
        return from != to && manager.active().isAvailable();
    }

    private String localization(Language from, String text) {
        if (from == Language.AUTO) {
            return detect(text).code();
        }
        return from.code();
    }
}