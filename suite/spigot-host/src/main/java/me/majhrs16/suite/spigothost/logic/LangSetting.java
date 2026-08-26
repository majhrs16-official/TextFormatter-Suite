package me.majhrs16.suite.spigothost.logic;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.UserLanguageStore;

/**
 * Pure semantics of a stored language value ({@code auto | off | código}),
 * shared by the directory (recipient resolution) and command handlers.
 */
public final class LangSetting {

    private LangSetting() {
    }

    /** @return trimmed value; blank becomes {@code auto}. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return UserLanguageStore.AUTO;
        }
        return raw.trim();
    }

    /** @return whether the raw input can be persisted as-is. */
    public static boolean isValid(String raw) {
        String value = normalize(raw);
        if (UserLanguageStore.AUTO.equalsIgnoreCase(value)
            || UserLanguageStore.OFF.equalsIgnoreCase(value)) {
            return true;
        }
        return Language.of(value).isPresent();
    }

    /** Next state when the user toggles: anything ≠ off becomes off, and back. */
    public static String flip(String stored) {
        return UserLanguageStore.OFF.equalsIgnoreCase(normalize(stored))
            ? UserLanguageStore.AUTO
            : UserLanguageStore.OFF;
    }

    /**
     * Effective language for rendering one recipient.
     *
     * @return {@link Language#AUTO} when stored is {@code off} (the engine
     *         then delivers source text), the stored code when valid, or
     *         {@code clientLocale} when unset/invalid.
     */
    public static Language effective(String stored, Language clientLocale) {
        if (UserLanguageStore.OFF.equalsIgnoreCase(stored)) {
            return Language.AUTO;
        }
        if (stored != null && !UserLanguageStore.AUTO.equalsIgnoreCase(stored)) {
            Language chosen = Language.of(stored).orElse(null);
            if (chosen != null) {
                return chosen;
            }
        }
        return clientLocale;
    }

    /** Human-friendly description for command feedback. */
    public static String display(String stored) {
        String value = normalize(stored);
        if (UserLanguageStore.OFF.equalsIgnoreCase(value)) {
            return "off (sin traducción)";
        }
        if (UserLanguageStore.AUTO.equalsIgnoreCase(value)) {
            return "auto (locale del cliente)";
        }
        return value;
    }
}
