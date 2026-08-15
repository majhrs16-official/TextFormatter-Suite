package me.majhrs16.cht.core.platform;

import me.majhrs16.cht.core.player.Subject;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Port exposing the set of currently connected players.
 *
 * <p>The router uses this to compute the recipient list of a chat message
 * without knowing anything about the underlying server.</p>
 */
public interface PlayerRegistry {

    /**
     * @return all currently online players as neutral subjects.
     */
    Collection<Subject> onlinePlayers();

    /**
     * @param name exact case-insensitive player name.
     * @return the online player with that name, if any.
     */
    Optional<Subject> playerByName(String name);

    /**
     * @param uuid player identity.
     * @return the online player with that UUID, if any.
     */
    Optional<Subject> playerByUuid(UUID uuid);
}