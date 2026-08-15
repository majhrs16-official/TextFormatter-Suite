package me.majhrs16.cht.core.translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Holds the configured translation providers and selects the active one.
 *
 * <p>Providers are added in priority order; {@link #active()} returns the
 * first available provider. If none is configured a no-op provider is used so
 * the engine never needs special casing.</p>
 */
public final class TranslatorManager {

    private final List<Translator> providers;

    public TranslatorManager() {
        this.providers = new ArrayList<>();
    }

    public TranslatorManager add(Translator provider) {
        providers.add(Objects.requireNonNull(provider, "provider"));
        return this;
    }

    /**
     * @return the first available provider, or a fallback that never throws.
     */
    public Translator active() {
        for (Translator provider : providers) {
            if (provider.isAvailable()) {
                return provider;
            }
        }
        return FALLBACK;
    }

    public List<Translator> providers() {
        return Collections.unmodifiableList(providers);
    }

    private static final Translator FALLBACK = new Translator() {
        @Override public String name() { return "none"; }

        @Override public String translate(String text, String from, String to) {
            return text;
        }

        @Override public String detect(String text) { return "en"; }

        @Override public boolean isAvailable() { return false; }
    };
}