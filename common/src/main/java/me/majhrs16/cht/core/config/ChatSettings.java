package me.majhrs16.cht.core.config;

import me.majhrs16.cht.core.language.Language;

import java.util.Objects;

/**
 * Typed subset of the plugin configuration the routing engine reads.
 *
 * <p>Values are immutable; the module that loads config files is responsible
 * for building a fresh instance after every reload.</p>
 */
public final class ChatSettings {

    private final Language defaultLanguage;
    private final boolean translateToSender;
    private final boolean translateSigns;
    private final boolean antiSpamEnabled;
    private final int antiSpamLimitPerTick;
    private final boolean connectionLostMarker;
    private final boolean debug;
    private final String translatorProvider;
    private final String libreUrl;
    private final String libreKey;

    private ChatSettings(Builder builder) {
        this.defaultLanguage = Objects.requireNonNull(builder.defaultLanguage, "defaultLanguage");
        this.translateToSender = builder.translateToSender;
        this.translateSigns = builder.translateSigns;
        this.antiSpamEnabled = builder.antiSpamEnabled;
        this.antiSpamLimitPerTick = builder.antiSpamLimitPerTick;
        this.connectionLostMarker = builder.connectionLostMarker;
        this.debug = builder.debug;
        this.translatorProvider = builder.translatorProvider;
        this.libreUrl = builder.libreUrl;
        this.libreKey = builder.libreKey;
    }

    public Language defaultLanguage() {
        return defaultLanguage;
    }

    /** When true the sender also sees their own message translated. */
    public boolean translateToSender() {
        return translateToSender;
    }

    public boolean translateSigns() {
        return translateSigns;
    }

    public boolean antiSpamEnabled() {
        return antiSpamEnabled;
    }

    public int antiSpamLimitPerTick() {
        return antiSpamLimitPerTick;
    }

    /** When true, failed translations are marked in the output. */
    public boolean connectionLostMarker() {
        return connectionLostMarker;
    }

    /** Extra debug logging for the platform loggers. */
    public boolean debug() {
        return debug;
    }

    /** Translator backend name: {@code google} or {@code libre}. */
    public String translatorProvider() {
        return translatorProvider;
    }

    /** LibreTranslate base url (only relevant for {@code libre}). */
    public String libreUrl() {
        return libreUrl;
    }

    /** Optional LibreTranslate api key. */
    public String libreKey() {
        return libreKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Language defaultLanguage = Language.EN;
        private boolean translateToSender = false;
        private boolean translateSigns = true;
        private boolean antiSpamEnabled = false;
        private int antiSpamLimitPerTick = 5;
        private boolean connectionLostMarker = true;
        private boolean debug = false;
        private String translatorProvider = "google";
        private String libreUrl = "http://localhost:5000";
        private String libreKey = "";

        public Builder defaultLanguage(Language defaultLanguage) {
            this.defaultLanguage = defaultLanguage;
            return this;
        }

        public Builder translateToSender(boolean translateToSender) {
            this.translateToSender = translateToSender;
            return this;
        }

        public Builder translateSigns(boolean translateSigns) {
            this.translateSigns = translateSigns;
            return this;
        }

        public Builder antiSpamEnabled(boolean antiSpamEnabled) {
            this.antiSpamEnabled = antiSpamEnabled;
            return this;
        }

        public Builder antiSpamLimitPerTick(int antiSpamLimitPerTick) {
            this.antiSpamLimitPerTick = antiSpamLimitPerTick;
            return this;
        }

        public Builder connectionLostMarker(boolean connectionLostMarker) {
            this.connectionLostMarker = connectionLostMarker;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder translatorProvider(String translatorProvider) {
            this.translatorProvider = translatorProvider;
            return this;
        }

        public Builder libreUrl(String libreUrl) {
            this.libreUrl = libreUrl;
            return this;
        }

        public Builder libreKey(String libreKey) {
            this.libreKey = libreKey;
            return this;
        }

        public ChatSettings build() {
            return new ChatSettings(this);
        }
    }
}