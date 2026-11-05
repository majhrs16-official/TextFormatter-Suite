package me.majhrs16.suite.messages;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centralized message catalog for TextFormatter Suite.
 *
 * <p>Loads message bundles from {@code messages/messages_<locale>.properties}
 * and provides locale-aware formatting with {@code {} } positional placeholders.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * MessagesCatalog catalog = MessagesCatalog.getInstance();
 * String msg = catalog.format(Locale.US, "enabled", 5, "google");
 * // => "[suite] active: 5 channels, translator 'google'"
 * }</pre>
 */
public final class MessagesCatalog {

    private static final String BUNDLE_BASE = "messages.messages";
    private static final MessagesCatalog INSTANCE = new MessagesCatalog();
    private final ConcurrentMap<Locale, ResourceBundle> cache = new ConcurrentHashMap<>();

    private MessagesCatalog() {}

    public static MessagesCatalog getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the formatted message for the given locale and key.
     *
     * @param locale the locale (falls back to English if missing)
     * @param key    the message key
     * @param args   positional arguments for {@code {} } placeholders
     * @return formatted message, or the key itself if not found
     */
    public String format(Locale locale, String key, Object... args) {
        String template = getTemplate(locale, key);
        return substitute(template, args);
    }

    /**
     * Returns the raw template for a key (without formatting).
     */
    public String getTemplate(Locale locale, String key) {
        ResourceBundle bundle = cache.computeIfAbsent(locale, this::loadBundle);
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            // Fallback to English
            if (locale != Locale.ENGLISH) {
                return getTemplate(Locale.ENGLISH, key);
            }
            return key;
        }
    }

    private ResourceBundle loadBundle(Locale locale) {
        return PropertyResourceBundle.getBundle("messages.messages", locale);
    }

    /**
     * Substitutes positional {@code {}} placeholders with arguments.
     */
    public static String substitute(String template, Object... args) {
        if (args == null || args.length == 0 || template == null) {
            return template == null ? "" : template;
        }
        String out = template;
        for (Object arg : args) {
            int at = out.indexOf("{}");
            if (at < 0) {
                break;
            }
            out = out.substring(0, at) + arg + out.substring(at + 2);
        }
        return out;
    }
}
