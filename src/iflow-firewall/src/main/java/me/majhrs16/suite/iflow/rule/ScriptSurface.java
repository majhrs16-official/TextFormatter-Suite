package me.majhrs16.suite.iflow.rule;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.ColorMode;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Surface of atomic operations exposed to SpEL scripts in iFlow rules.
 * <p>
 * This is the <b>root object</b> available as {@code #surface} in condition/action expressions.
 * All methods are safe, side-effect-free (except those explicitly marked),
 * and designed for use in SpEL expressions.
 * </p>
 * <p>
 * <b>Usage in rules.yml:</b>
 * </p>
 * <pre>
 * conditions:
 *   - "#surface.hasPermission(#msg.sender, 'cht.staff')"
 *   - "#surface.papi('%vault_eco_balance%') > 1000"
 * actions:
 *   - "#surface.setLangTarget(#msg, 'en')"
 *   - "#surface.cancel()"
 * </pre>
 */
public final class ScriptSurface {

    private Message message;
    private final Actor sender;
    private final Actor recipient;
    private final ChannelRegistry channels;
    private final PlaceholderResolver placeholders;
    private final TranslationService translation;
    private final BiFunction<Actor, String, Boolean> permissionChecker;

    private String redirectChannel;
    private boolean cancelled = false;
    private boolean skipTranslate = false;
    private String formatPath;

    public ScriptSurface(Message message,
                         Actor sender,
                         Actor recipient,
                         ChannelRegistry channels,
                         PlaceholderResolver placeholders,
                         TranslationService translation,
                         BiFunction<Actor, String, Boolean> permissionChecker) {
        this.message = Objects.requireNonNull(message, "message");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.recipient = Objects.requireNonNull(recipient, "recipient");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.placeholders = placeholders;
        this.translation = translation;
        this.permissionChecker = permissionChecker;
    }

    // ============================================================
    // QUERY OPERATIONS (read-only, safe for conditions)
    // ============================================================

    /** @return the message being processed. */
    public Message msg() {
        return message;
    }

    /** @return the sender (emitter) of the message. */
    public Actor sender() {
        return sender;
    }

    /** @return the recipient this rule is being evaluated for. */
    public Actor recipient() {
        return recipient;
    }

    /** @return the channel registry. */
    public ChannelRegistry channels() {
        return channels;
    }

    /** @return whether the given actor has the specified permission. */
    public boolean hasPermission(Actor actor, String permission) {
        if (permission == null || permission.isBlank()) return true;
        return permissionChecker.apply(actor, permission);
    }

    /** @return whether the sender has the specified permission. */
    public boolean senderHasPermission(String permission) {
        return hasPermission(sender, permission);
    }

    /** @return whether the recipient has the specified permission. */
    public boolean recipientHasPermission(String permission) {
        return hasPermission(recipient, permission);
    }

    /** Resolves a PlaceholderAPI placeholder for the sender. */
    public String papi(String token) {
        if (placeholders != null && placeholders.available()) {
            return placeholders.resolve(sender, token);
        }
        return "";
    }

    /** Resolves a PlaceholderAPI placeholder for the recipient. */
    public String papiRecipient(String token) {
        if (placeholders != null && placeholders.available()) {
            return placeholders.resolve(recipient, token);
        }
        return "";
    }

    /** Resolves a PlaceholderAPI placeholder for any actor. */
    public String papi(Actor actor, String token) {
        if (placeholders != null && placeholders.available()) {
            return placeholders.resolve(actor, token);
        }
        return "";
    }

    /** @return true if translation service is available. */
    public boolean canTranslate() {
        return translation != null && translation.isAvailable();
    }

    /** @return the channel that produced this message. */
    public Channel channel() {
        return channels.resolve(message.channel());
    }

    // ============================================================
    // MUTATION OPERATIONS (side-effecting, for actions)
    // ============================================================

    /** Sets the target language for this message's translation. */
    public void setLangTarget(Language lang) {
        this.message = message.withLangTarget(lang);
    }

    /** Sets the source language for this message's translation. */
    public void setLangSource(Language lang) {
        this.message = message.withLangSource(lang);
    }

    /** Disables translation for this message. */
    public void skipTranslate() {
        this.skipTranslate = true;
        this.message = message.withTranslate(false);
    }

    /** Enables translation for this message. */
    public void enableTranslate() {
        this.message = message.withTranslate(true);
    }

    /** Sets the format path (channel) for this message. */
    public void setFormat(String path) {
        this.formatPath = path;
        this.message = message.withChannel(path);
    }

    /** Sets the color mode for this message. */
    public void setColorMode(String mode) {
        this.message = message.withColorMode(ColorMode.valueOf(mode.toUpperCase()));
    }

    /** Sets whether PAPI placeholders should be resolved. */
    public void setFormatPapi(boolean enabled) {
        this.message = message.withFormatPapi(enabled);
    }

    /** Cancels the message delivery entirely (DROP). */
    public void cancel() {
        this.cancelled = true;
        this.message = message.withCancelled(true);
    }

    /** Marks the message as processed without delivery (internal). */
    public void setProcessed() {
        this.message = message.withProcessed(true);
    }

    /** Sets a redirect target channel (for CHANNEL_REDIRECT). */
    public void redirect(String channelPath) {
        this.redirectChannel = channelPath;
    }

    /** @return whether the message was cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** @return whether translation should be skipped. */
    public boolean isSkipTranslate() {
        return skipTranslate;
    }

    /** @return the redirect channel if set, null otherwise. */
    public String getRedirectChannel() {
        return redirectChannel;
    }

    /** @return the format path if set, null otherwise. */
    public String getFormatPath() {
        return formatPath;
    }

    // ============================================================
    // TRANSFORM OPERATIONS (for F7+ transform ops)
    // ============================================================

    /** Sets the raw message text (for Rewrite transform). */
    public void setText(String text) {
        this.message = message.withText(text);
    }

    /** Adds sound specs to the message (for Sounds transform). */
    public void setSoundsAdd(List<String> sounds) {
        this.message = message.withSoundsAdd(sounds);
    }

    /** Removes sound specs from the message (for Sounds transform). */
    public void setSoundsRemove(List<String> sounds) {
        this.message = message.withSoundsRemove(sounds);
    }

    /** Sets sleep milliseconds (for Sleep transform). */
    public void setSleepMillis(long millis) {
        this.message = message.withSleepMillis(millis);
    }

    // ============================================================
    // HELPER / UTILITY
    // ============================================================

    /** Creates a deep copy of the message for safe mutation. */
    public Message cloneMessage() {
        return message.clone();
    }

    /** Serializes the message to JSON for debugging. */
    public String toJson() {
        return toJsonString(message);
    }

    private static String toJsonString(Message msg) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendField(sb, "id", msg.id().toString());
        appendField(sb, "type", msg.type().name());
        appendField(sb, "sender", msg.sender().name());
        appendField(sb, "direction", msg.direction().toString());
        appendField(sb, "text", escapeJson(msg.text()));
        appendField(sb, "channel", msg.channel());
        appendField(sb, "langSource", msg.langSource() != null ? msg.langSource().code() : null);
        appendField(sb, "langTarget", msg.langTarget() != null ? msg.langTarget().code() : null);
        appendField(sb, "resolvedSourceLanguage", msg.resolvedSourceLanguage() != null ? msg.resolvedSourceLanguage().code() : null);
        appendField(sb, "translate", msg.shouldTranslate());
        appendField(sb, "cancelled", msg.isCancelled());
        appendField(sb, "show", msg.isShown());
        appendField(sb, "formatPapi", msg.formatPapi());
        appendField(sb, "colorMode", msg.colorMode().name());
        appendField(sb, "sleepMillis", msg.sleepMillis());
        if (sb.length() > 1) sb.setLength(sb.length() - 2);
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, Object value) {
        if (value == null) return;
        sb.append('"').append(name).append("\":");
        if (value instanceof String) {
            sb.append('"').append(escapeJson((String) value)).append('"');
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else {
            sb.append('"').append(escapeJson(value.toString())).append('"');
        }
        sb.append(',');
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // Static helpers for SpEL convenience

    /** @return true if the message type matches any of the given types. */
    public static boolean typeIs(Message msg, MessageType... types) {
        for (MessageType t : types) {
            if (msg.type() == t) return true;
        }
        return false;
    }

    /** @return true if the direction kind matches. */
    public static boolean directionIs(Direction dir, Direction.Kind kind) {
        return dir != null && dir.kind() == kind;
    }

    // Required for record-like behavior in SpEL
    @Override
    public String toString() {
        return "ScriptSurface(msg=" + message.id() + ")";
    }
}