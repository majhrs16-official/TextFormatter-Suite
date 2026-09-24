package me.majhrs16.suite.host.port;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;

import net.kyori.adventure.text.Component;

/**
 * Output port the platform host implements to push rendered output into
 * Minecraft. Lives beside {@code RoutingResult} (not in {@code core-api})
 * because its contract carries Adventure {@link Component}s — core-api stays
 * dependency-free.
 *
 * <p>Implementations decide threading: the engine may call from any thread,
 * so implementations must hop back to the main thread when the platform
 * requires it.</p>
 */
public interface ChatDelivery {

    /**
     * Shows a rendered message to one recipient.
     *
     * @param recipient target actor; use its native handle when present.
     * @param rendered  fully rendered component (already localized for this
     *                  recipient by TextFormatter).
     * @param original  the message that produced {@code rendered}, for
     *                  adapters that need extra context.
     */
    void deliver(Actor recipient, Component rendered, Message original);

    /**
     * Shows rendered output on the server console. Used only for REDIRECT
     * audit copies; a {@code Direction} of kind CONSOLE arrives here as a
     * normal {@link #deliver} with the console {@link Actor}.
     */
    void deliverConsole(Component rendered);

    /** Plays one sound spec at the recipient. */
    void playSound(Actor recipient, SoundSpec sound);

    /**
     * @return whether the platform registry knows {@code soundName}; unknown
     *         names are skipped instead of throwing.
     */
    boolean hasSound(String soundName);
}
