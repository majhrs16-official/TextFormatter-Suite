package me.majhrs16.suite.synccommon;

import java.util.List;
import java.util.Optional;

/**
 * Platform environment exposed by a local sync backend.
 *
 * <p>Implementations live in the platform packages ({@code sync-bukkit},
 * {@code sync-fabric}) and keep every Bukkit or Fabric type inside them. The
 * local sink only sees this neutral seam, publishes one action per operation
 * and maps every failure explicitly.</p>
 */
public interface LocalEnvironment {

    /** @return a snapshot of the players currently online, in platform order */
    List<LocalPlayer> players();

    /**
     * Finds one online player by name or UUID.
     *
     * @param reference player name or UUID string
     * @return the player when online; empty otherwise
     */
    Optional<LocalPlayer> player(String reference);

    /**
     * Sends a private message to one online player.
     *
     * @throws LocalActionException when the player is not online or the
     *         platform cannot deliver the message
     */
    void sendMessage(String reference, String text);

    /**
     * Kicks one online player.
     *
     * @throws LocalActionException when the player is not online or the
     *         platform cannot kick it
     */
    void kick(String reference, String reason);

    /**
     * Runs a command on the platform console.
     *
     * @throws LocalActionException when the command is rejected by the
     *         platform
     */
    void runConsoleCommand(String command);

    /**
     * Sets one score on an existing scoreboard objective.
     *
     * @throws LocalActionException when the objective does not exist or the
     *         score cannot be written
     */
    void setScore(String objective, String entry, int value);

    /**
     * Returns the event bound to the current message, if any.
     *
     * @return the bound event; empty when the message has no platform event
     */
    Optional<LocalEvent> currentEvent();
}
