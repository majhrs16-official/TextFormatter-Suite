package me.majhrs16.suite.fabrichost.logic;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import java.util.Locale;
import java.util.Optional;

/**
 * Shared language logic between {@link FabricActorDirectory} and commands.
 */
public final class LangSetting {

    private LangSetting() {}

    public static final String AUTO = UserLanguageStore.AUTO;

    public static String normalize(String value) {
        if (value == null) return AUTO;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "off".equals(v) ? "off" : v;
    }

    public static String display(String normalized) {
        return "off".equals(normalized) ? "off" : normalized;
    }

    public static boolean isValid(String normalized) {
        return "off".equals(normalized)
            || Language.of(normalized).isPresent()
            || Language.of(normalized.split("_")[0]).isPresent();
    }

    public static String flip(String normalized) {
        return "off".equals(normalized) ? AUTO : "off";
    }

    public static Optional<Language> effective(String stored, Language client) {
        if (stored == null) return Optional.ofNullable(client);
        if ("off".equals(stored)) return Optional.of(Language.AUTO);
        return Language.of(stored).or(() -> Optional.ofNullable(client));
    }

    public static boolean shouldTranslate(UserLanguageStore store, java.util.UUID uuid) {
        String val = store == null ? AUTO : store.languageOf(uuid).orElse(AUTO);
        return !"off".equals(val);
    }
}
