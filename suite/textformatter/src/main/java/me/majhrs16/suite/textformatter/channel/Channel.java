package me.majhrs16.suite.textformatter.channel;

import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.SoundSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named destination with a format tail and an access policy.
 *
 * <p>This is the renamed, modern version of the v4 format group: the route a
 * message is pinned to. It carries the MiniMessage formats/tooltips/sounds,
 * the default language pair and the permission model, which supports both
 * idioms at once:</p>
 *
 * <ul>
 *   <li><b>Base subscription</b> — a single {@link #permission()}: possessing
 *       it means being subscribed to the channel (default ACCEPT when unset).</li>
 *   <li><b>Explicit asymmetry</b> — optional {@link #sendPermission()} and
 *       {@link #receivePermission()} override the base permission for a
 *       specific side (e.g. everyone reads, only staff writes).</li>
 * </ul>
 */
public final class Channel {

    /** Default placeholder consumed by every format template. */
    public static final String PLACEHOLDER = "%ct_messages%";

    private final String name;
    private final Formats messages;
    private final Formats tooltips;
    private final List<SoundSpec> sounds;
    private final Language langSource;
    private final Language langTarget;
    private final String permission;
    private final String sendPermission;
    private final String receivePermission;
    private final boolean showSender;
    private final int rateLimitPerSecond;

    private Channel(Builder builder) {
        this.name = builder.name;
        this.messages = builder.messages != null ? builder.messages : Formats.empty();
        this.tooltips = builder.tooltips != null ? builder.tooltips : Formats.empty();
        this.sounds = builder.sounds == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(builder.sounds));
        this.langSource = builder.langSource != null ? builder.langSource : Language.AUTO;
        this.langTarget = builder.langTarget != null ? builder.langTarget : Language.AUTO;
        this.permission = builder.permission;
        this.sendPermission = builder.sendPermission;
        this.receivePermission = builder.receivePermission;
        this.showSender = builder.showSender;
        this.rateLimitPerSecond = builder.rateLimitPerSecond;
    }

    /** @return unique dotted path of this channel, e.g. {@code chat} or {@code private.other}. */
    public String name() {
        return name;
    }

    public Formats messages() {
        return messages;
    }

    public Formats tooltips() {
        return tooltips;
    }

    public List<SoundSpec> sounds() {
        return sounds;
    }

    public Language langSource() {
        return langSource;
    }

    public Language langTarget() {
        return langTarget;
    }

    /** @return base subscription permission; {@code null} means ACCEPT. */
    public String permission() {
        return permission;
    }

    /** @return optional sender-side override; {@code null} falls back to base. */
    public String sendPermission() {
        return sendPermission;
    }

    /** @return optional receiver-side override; {@code null} falls back to base. */
    public String receivePermission() {
        return receivePermission;
    }

    /** @return whether the sender can see their own emitted message. */
    public boolean showSender() {
        return showSender;
    }

    /** @return messages-per-second limit, 0 means unlimited. */
    public int rateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    /** Effective permission for the sending side. */
    public String sendPolicy() {
        return sendPermission != null ? sendPermission : permission;
    }

    /** Effective permission for the receiving side. */
    public String receivePolicy() {
        return receivePermission != null ? receivePermission : permission;
    }

    @Override
    public String toString() {
        return "Channel{" + name + "}";
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private Formats messages;
        private Formats tooltips;
        private List<SoundSpec> sounds;
        private Language langSource;
        private Language langTarget;
        private String permission;
        private String sendPermission;
        private String receivePermission;
        private boolean showSender = true;
        private int rateLimitPerSecond;

        private Builder(String name) {
            this.name = name;
        }

        public Builder messages(Formats messages) {
            this.messages = messages;
            return this;
        }

        public Builder tooltips(Formats tooltips) {
            this.tooltips = tooltips;
            return this;
        }

        public Builder sounds(List<SoundSpec> sounds) {
            this.sounds = new ArrayList<>(sounds);
            return this;
        }

        public Builder langSource(Language langSource) {
            this.langSource = langSource;
            return this;
        }

        public Builder langTarget(Language langTarget) {
            this.langTarget = langTarget;
            return this;
        }

        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        public Builder sendPermission(String sendPermission) {
            this.sendPermission = sendPermission;
            return this;
        }

        public Builder receivePermission(String receivePermission) {
            this.receivePermission = receivePermission;
            return this;
        }

        public Builder showSender(boolean showSender) {
            this.showSender = showSender;
            return this;
        }

        public Builder rateLimitPerSecond(int rateLimitPerSecond) {
            this.rateLimitPerSecond = rateLimitPerSecond;
            return this;
        }

        public Channel build() {
            return new Channel(this);
        }
    }
}