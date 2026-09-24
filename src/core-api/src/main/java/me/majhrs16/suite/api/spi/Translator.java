package me.majhrs16.suite.api.spi;

/**
 * A translation backend. Implementations are synchronous and blocking; the
 * routing engine is responsible for running them off the main thread.
 *
 * <p>The engine only ever works with language codes (see {@code Language});
 * a provider may support a different, shorter set than the full catalog.</p>
 */
public interface Translator {

    /** @return a stable provider name, e.g. {@code google} or {@code libre}. */
    String name();

    /**
     * Translates a single text fragment.
     *
     * @param text the source text; never null but may be empty.
     * @param from source language code ({@code auto} disables detection).
     * @param to   target language code.
     * @return the translated text, or the input untouched when it is empty.
     * @throws TranslationException when the backend is unavailable or replies
     *                              with an error.
     */
    String translate(String text, String from, String to) throws TranslationException;

    /**
     * Detects the language a text is written in.
     *
     * @param text the sample text.
     * @return a detected language code, never null; {@code en} on failure.
     */
    String detect(String text);

    /** @return whether the provider can be used right now. */
    boolean isAvailable();
}