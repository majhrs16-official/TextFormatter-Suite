package me.majhrs16.cht.core.player;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A chat-capable entity in a platform-neutral way.
 *
 * <p>This replaces the former reliance on Bukkit's {@code CommandSender}.
 * A {@code Subject} carries only the information the formatting and routing
 * engine needs, plus an opaque native handle that the platform adapter uses to
 * actually deliver the message or resolve side effects.</p>
 */
public final class Subject {

    private final UUID uuid;
    private final String name;
    private final SubjectKind kind;
    private final Object nativeHandle;

    /**
     * @param uuid         stable identity, may be {@code null} for console/unknown.
     * @param name         display name.
     * @param kind         kind of subject.
     * @param nativeHandle opaque platform handle, may be {@code null}.
     */
    public Subject(UUID uuid, String name, SubjectKind kind, Object nativeHandle) {
        this.uuid = uuid;
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.nativeHandle = nativeHandle;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public SubjectKind kind() {
        return kind;
    }

    /**
     * The opaque platform handle. Callers must inspect its runtime type before
     * using it; on Spigot it will be a {@code CommandSender}, on Fabric a
     * {@code ServerPlayerEntity} or the console object.
     */
    @SuppressWarnings("unchecked")
    public <T> T handle() {
        return (T) nativeHandle;
    }

    public boolean isPlayer() {
        return kind == SubjectKind.PLAYER;
    }

    public Locale locale() {
        return (uuid == null || kind == SubjectKind.PLAYER)
            ? Locale.forLanguageTag("en")
            : Locale.ROOT;
    }

    @Override
    public String toString() {
        return kind + "{" + name + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Subject)) {
            return false;
        }
        Subject that = (Subject) other;
        return Objects.equals(uuid, that.uuid) && kind == that.kind
            && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, kind, name);
    }

    public static Subject console(String name) {
        return new Subject(null, name, SubjectKind.CONSOLE, null);
    }

    public static Subject unknown(String name) {
        return new Subject(null, name, SubjectKind.UNKNOWN, null);
    }

    /** Java 8 friendly enum holder. */
    public enum SubjectKind {
        PLAYER,
        CONSOLE,
        UNKNOWN,
    }
}
