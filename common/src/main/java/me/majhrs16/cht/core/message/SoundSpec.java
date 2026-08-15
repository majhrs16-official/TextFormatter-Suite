package me.majhrs16.cht.core.message;

import java.util.Objects;

/**
 * A sound attached to a rendered message.
 *
 * <p>Immutable value type. The adapter decides how to map {@code name} onto
 * the platform registry (Bukkit sound enum / Fabric sound event).</p>
 */
public final class SoundSpec {

    private final String name;
    private final float volume;
    private final float pitch;

    public SoundSpec(String name, float volume, float pitch) {
        this.name = Objects.requireNonNull(name, "name");
        this.volume = volume;
        this.pitch = pitch;
    }

    public String name() {
        return name;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    @Override
    public String toString() {
        return name + " vol=" + volume + " pitch=" + pitch;
    }
}