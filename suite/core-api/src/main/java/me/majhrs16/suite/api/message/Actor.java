package me.majhrs16.suite.api.message;

import java.util.Objects;
import java.util.UUID;

/**
 * A chat participant (sender or recipient) in a platform-neutral way.
 */
public final class Actor {

    private final UUID uuid;
    private final String name;
    private final ActorKind kind;
    private final Language language;
    private final Object nativeHandle;

    public Actor(UUID uuid, String name, ActorKind kind, Language language, Object nativeHandle) {
        this.uuid = uuid;
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.language = language;
        this.nativeHandle = nativeHandle;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public ActorKind kind() {
        return kind;
    }

    /** The language associated with this actor, may be {@code null}. */
    public Language language() {
        return language;
    }

    /** Opaque platform handle; inspect its runtime type before using it. */
    @SuppressWarnings("unchecked")
    public <T> T handle() {
        return (T) nativeHandle;
    }

    public boolean isPlayer() {
        return kind == ActorKind.PLAYER;
    }

    public boolean isConsole() {
        return kind == ActorKind.CONSOLE;
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
        if (!(other instanceof Actor)) {
            return false;
        }
        Actor that = (Actor) other;
        return Objects.equals(uuid, that.uuid) && kind == that.kind
            && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, kind, name);
    }

    /** @return a copy of this actor with a different associated language. */
    public Actor withLanguage(Language language) {
        return new Actor(uuid, name, kind, language, nativeHandle);
    }

    public static Actor console(String name, Language language) {
        return new Actor(null, name, ActorKind.CONSOLE, language, null);
    }

    public static Actor unknown(String name) {
        return new Actor(null, name, ActorKind.UNKNOWN, null, null);
    }

    public enum ActorKind {
        PLAYER,
        CONSOLE,
        UNKNOWN,
    }
}