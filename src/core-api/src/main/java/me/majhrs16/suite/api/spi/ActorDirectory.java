package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Actor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of the platform population a {@code Message} can be routed
 * to. Implemented by the platform host (Spigot/Fabric/console harness); the
 * engine uses it to expand a {@link me.majhrs16.suite.api.message.Direction}
 * into concrete recipient actors.
 *
 * <p>Implementations must be thread-safe: routing may run off the main
 * thread.</p>
 */
public interface ActorDirectory {

    /** @return every currently connected player, in stable platform order. */
    List<Actor> onlinePlayers();

    /** @return the connected player with this unique id, if any. */
    Optional<Actor> byUuid(UUID uuid);

    /** @return the connected player with this exact name, if any. */
    Optional<Actor> byName(String name);

    /** @return the server console actor (never {@code null}). */
    Actor console();

    /**
     * Players connected to one world.
     *
     * <p>Default returns an empty list: platforms without world resolution
     * simply deliver to nobody until they override it.</p>
     *
     * @param world platform world name (e.g. {@code "world_nether"}).
     */
    default List<Actor> playersInWorld(String world) {
        return List.of();
    }

    /**
     * Players within {@code radiusBlocks} of {@code center}.
     *
     * <p>Default returns an empty list, same rationale as
     * {@link #playersInWorld(String)}.</p>
     */
    default List<Actor> playersNear(Actor center, double radiusBlocks) {
        return List.of();
    }
}
