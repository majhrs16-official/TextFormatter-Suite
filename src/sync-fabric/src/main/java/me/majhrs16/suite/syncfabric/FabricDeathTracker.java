package me.majhrs16.suite.syncfabric;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players have already produced a death event.
 *
 * <p>Fabric 1.21 has no stable player-death callback in the API used by this
 * package, so the listener observes the server tick and emits once per death.
 * The tracker is the small, testable state machine behind that observation.</p>
 */
public final class FabricDeathTracker {

    private final Set<UUID> recentlyDead = ConcurrentHashMap.newKeySet();

    /** @return {@code true} only the first time this player is marked dead. */
    public boolean markDead(UUID playerId) {
        return playerId != null && recentlyDead.add(playerId);
    }

    /** Clears the death mark after the player respawns. */
    public void markAlive(UUID playerId) {
        if (playerId != null) {
            recentlyDead.remove(playerId);
        }
    }

    public boolean isMarked(UUID playerId) {
        return playerId != null && recentlyDead.contains(playerId);
    }

    public void clear() {
        recentlyDead.clear();
    }
}
