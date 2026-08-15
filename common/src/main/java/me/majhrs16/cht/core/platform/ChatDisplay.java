package me.majhrs16.cht.core.platform;

import me.majhrs16.cht.core.message.SoundSpec;
import me.majhrs16.cht.core.player.Channel;
import me.majhrs16.cht.core.player.Subject;

import net.kyori.adventure.text.Component;

import javax.annotation.Nullable;

/**
 * Port used to deliver rendered messages into the host platform.
 *
 * <p>Implementations (Spigot, Fabric, Discord) translate the neutral
 * {@link Component} into whatever the target channel understands: a JSON chat
 * component, a plain console line or a Discord webhook.</p>
 */
public interface ChatDisplay {

    /**
     * Delivers a rich {@link Component} to a single recipient.
     *
     * @param recipient the target subject; never null.
     * @param message   the rendered component.
     * @param channel   the channel the component was rendered for.
     */
    void send(Subject recipient, Component message, Channel channel);

    /**
     * Plays a sound to a recipient (if the platform supports it).
     *
     * @param recipient target player.
     * @param sound     the sound specification.
     */
    void playSound(Subject recipient, SoundSpec sound);

    /**
     * Whether the platform knows a sound key. Used to warn about mistyped
     * sound names in formats. Defaults to {@code true} so ports that do not
     * own a sound registry never filter sounds out.
     *
     * @param key the sound namespace key (e.g. {@code entity.experience_orb.pickup}).
     * @return {@code true} when the sound is (believed to be) registered.
     */
    default boolean hasSound(String key) {
        return true;
    }

    /**
     * Sends a message to the server console without any formatting metadata.
     *
     * @param message plain text.
     */
    void sendToConsole(String message);

    /**
     * Executes a raw command as the server console.
     *
     * @param command the command line to run.
     */
    void dispatchServerCommand(String command);

    /**
     * Runs a command as a player-like subject.
     *
     * @param actor   the invoking subject.
     * @param command the command line.
     */
    void dispatchCommand(Subject actor, String command, @Nullable Channel channel);
}