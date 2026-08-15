package me.majhrs16.cht.core.message;

import me.majhrs16.cht.core.player.Channel;

import java.util.Objects;

/**
 * The audience a message is routed to.
 *
 * <p>There is no implicit from/to pairing in the engine: a {@code Message} is
 * routed by its {@link Direction}, which is resolved at routing time into the
 * concrete list of recipient {@link Actor actors}. The message that goes back
 * to the actor who triggered the event is simply another {@code Message} routed
 * with {@link Kind#INITIATOR}; the broadcast is {@link Kind#OTHERS}. Both are
 * independent units and can be formatted, translated and cancelled separately.
 * No two-element array exists anywhere.</p>
 */
public final class Direction {

    /** The actor who initiated the chat event. */
    private final Kind kind;
    /** Channel the routed message will be sent through. */
    private final Channel channel;
    /** Name/permission/radius/world qualifier when the kind needs one. */
    private final String qualifier;
    /** Explicit recipients, only meaningful for {@link Kind#SPECIFIC}. */
    private final Actor[] recipients;

    Direction(Kind kind, Channel channel, String qualifier, Actor[] recipients) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.qualifier = qualifier;
        this.recipients = recipients;
    }

    public Kind kind() {
        return kind;
    }

    public Channel channel() {
        return channel;
    }

    /** @return qualifier, may be {@code null}. */
    public String qualifier() {
        return qualifier;
    }

    /**
     * @return explicit recipients; only non-empty for {@link Kind#SPECIFIC}.
     */
    public Actor[] recipients() {
        return recipients == null ? new Actor[0] : recipients.clone();
    }

    @Override
    public String toString() {
        return "Direction[" + kind + (qualifier != null ? "," + qualifier : "") + "]";
    }

    /** Back to the actor that started the event (the former "from"). */
    public static Direction initiator() {
        return new Direction(Kind.INITIATOR, Channel.CHAT, null, (Actor[]) null);
    }

    /** Broadcast to every online player except the initiator (former "to"). */
    public static Direction others() {
        return new Direction(Kind.OTHERS, Channel.CHAT, null, (Actor[]) null);
    }

    /** Broadcast to every online player. */
    public static Direction all() {
        return new Direction(Kind.ALL, Channel.CHAT, null, (Actor[]) null);
    }

    /** Server console. */
    public static Direction console() {
        return new Direction(Kind.CONSOLE, Channel.CONSOLE, null, (Actor[]) null);
    }

    /** Players connected to a specific world. */
    public static Direction world(String world) {
        return new Direction(Kind.WORLD, Channel.CHAT, world, (Actor[]) null);
    }

    /** Players within {@code radius} blocks of the initiator. */
    public static Direction radius(double radius) {
        return new Direction(Kind.RADIUS, Channel.CHAT, String.valueOf(radius), (Actor[]) null);
    }

    /** Players holding a permission (e.g. a LuckPerms-style rank). */
    public static Direction permission(String permission) {
        return new Direction(Kind.PERMISSION, Channel.CHAT, permission, (Actor[]) null);
    }

    /** An explicit, fixed set of recipients. */
    public static Direction specific(Channel channel, Actor... recipients) {
        return new Direction(Kind.SPECIFIC, channel, null, recipients);
    }

    /** A direction over another channel but same audience semantics. */
    public Direction channel(Channel channel) {
        return new Direction(kind, channel, qualifier, (Actor[]) recipients);
    }

    public enum Kind {
        INITIATOR,
        OTHERS,
        ALL,
        CONSOLE,
        WORLD,
        RADIUS,
        PERMISSION,
        SPECIFIC,
    }
}