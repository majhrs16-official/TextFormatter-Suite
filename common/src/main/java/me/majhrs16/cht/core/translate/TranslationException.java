package me.majhrs16.cht.core.translate;

/**
 * Thrown when a translation backend cannot produce a translation.
 */
public class TranslationException extends RuntimeException {

    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}