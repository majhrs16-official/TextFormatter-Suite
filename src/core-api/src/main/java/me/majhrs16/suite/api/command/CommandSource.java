package me.majhrs16.suite.api.command;

import me.majhrs16.suite.api.message.Actor;

/**
 * Platform-neutral origin of a command invocation.
 *
 * <p>The runtime adapter translates its sender into an {@link Actor} and
 * implements permission checks and replies through this interface. Modules do
 * not import Bukkit or Fabric.</p>
 */
public interface CommandSource {

    /** @return the actor that invoked the command. */
    Actor actor();

    /**
     * Checks a command permission.
     *
     * @param permission permission node; never {@code null}
     * @return {@code true} when the actor may execute the command
     */
    boolean hasPermission(String permission);

    /**
     * Sends one feedback line to the actor.
     *
     * @param message plain text, already localized by the caller
     */
    void reply(String message);

    /** @return the actor name, for messages that do not need the whole actor. */
    default String name() {
        return actor().name();
    }
}
