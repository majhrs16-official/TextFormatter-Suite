package me.majhrs16.suite.syncfabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class FabricDeathTrackerTest {

    @Test
    void emitsOnlyOnceUntilRespawn() {
        FabricDeathTracker tracker = new FabricDeathTracker();
        UUID player = UUID.randomUUID();

        assertTrue(tracker.markDead(player));
        assertFalse(tracker.markDead(player));
        assertTrue(tracker.isMarked(player));

        tracker.markAlive(player);
        assertFalse(tracker.isMarked(player));
        assertTrue(tracker.markDead(player));
    }

    @Test
    void clearRemovesEveryMark() {
        FabricDeathTracker tracker = new FabricDeathTracker();
        UUID player = UUID.randomUUID();
        tracker.markDead(player);

        tracker.clear();

        assertFalse(tracker.isMarked(player));
    }
}
