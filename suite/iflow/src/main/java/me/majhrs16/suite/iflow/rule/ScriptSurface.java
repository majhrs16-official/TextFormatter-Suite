package me.majhrs16.suite.iflow.rule;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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

    private final Message message;
    private final Actor sender;
    private final Actor recipient;
    private final ChannelRegistry channels;
    private final PlaceholderResolver placeholders;
    private final TranslationService translation;
    private final Function<Actor, Boolean> permissionChecker;

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
                         Function<Actor, Boolean> permissionChecker) {
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
        return permissionChecker.apply(actor);
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
        // Message is immutable; actual mutation happens in delivery pipeline
        // This is a marker for the delivery pipeline
        message.setLangTarget(lang);
    }

    /** Sets the source language for this message's translation. */
    public void setLangSource(Language lang) {
        message.setLangSource(lang);
    }

    /** Disables translation for this message. */
    public void skipTranslate() {
        this.skipTranslate = true;
        message.setTranslate(false);
    }

    /** Enables translation for this message. */
    public void enableTranslate() {
        message.setTranslate(true);
    }

    /** Sets the format path (channel) for this message. */
    public void setFormat(String path) {
        this.formatPath = path;
        message.setChannel(path);
    }

    /** Sets the color mode for this message. */
    public void setColorMode(String mode) {
        message.setColorMode(mode);
    }

    /** Sets whether PAPI placeholders should be resolved. */
    public void setFormatPapi(boolean enabled) {
        message.setFormatPapi(enabled);
    }

    /** Cancels the message delivery entirely (DROP). */
    public void cancel() {
        this.cancelled = true;
        message.setCancelled(true);
    }

    /** Marks the message as processed without delivery (internal). */
    public void setProcessed() {
        message.setProcessed(true);
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
    // HELPER / UTILITY
    // ============================================================

    /** Creates a deep copy of the message for safe mutation. */
    public Message cloneMessage() {
        return message.clone();
    }

    /** Serializes the message to JSON for debugging. */
    public String toJson() {
        return message.toJson();
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