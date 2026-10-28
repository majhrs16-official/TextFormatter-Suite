package me.majhrs16.suite.api.runtime;

import java.util.List;
import java.util.Optional;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

/**
 * Platform-facing ports implemented by the thin adapters.
 *
 * <p>An adapter is the only layer that knows Bukkit or Fabric. It implements
 * these ports, translates platform events into {@link Message} instances and
 * delegates the rest to the runtime. No feature module implements or imports
 * this interface; features see the neutral runtime contracts and services.</p>
 */
public interface PlatformPorts {

    /** @return neutral information about the hosting platform. */
    PlatformInfo info();

    /**
     * Delivers a fully formatted message to the audience selected by
     * {@link Message#direction()}.
     *
     * @param message message to deliver
     */
    void deliver(Message message);

    /**
     * Finds an online actor by name.
     *
     * @param name actor name
     * @return the actor, or empty if it is not online
     */
    Optional<Actor> findActor(String name);

    /** @return a snapshot of the currently online actors. */
    List<Actor> onlineActors();

    /**
     * Registers a command exposed by the runtime through the platform.
     *
     * @param command command to register
     * @return a handle that unregisters the command
     */
    Registration registerCommand(Command command);

    /** Platform-neutral command contract. */
    interface Command {

        /** @return the command name without the leading slash. */
        String name();

        /** @return command aliases; may be empty. */
        List<String> aliases();

        /** @return a short description for the platform help output. */
        String description();

        /**
         * Executes the command.
         *
         * @param sender actor that invoked it
         * @param args   arguments after the command name
         */
        void execute(Actor sender, List<String> args);
    }

    /** Handle returned by {@link #registerCommand(Command)}. */
    interface Registration extends AutoCloseable {

        /** Unregisters the command. Idempotent. */
        void close();
    }
}
