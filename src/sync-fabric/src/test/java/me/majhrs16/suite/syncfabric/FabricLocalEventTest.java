package me.majhrs16.suite.syncfabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import me.majhrs16.suite.synccommon.LocalActionException;
import me.majhrs16.suite.synccommon.LocalEvent;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the Fabric event binding.
 *
 * <p>Minecraft registries are not bootstrapped in the plain test JVM, so the
 * server-backed environment is exercised by compilation against the mapped
 * API and by the shared {@code LocalSyncSinkTest}. This class covers the
 * neutral event contract that does not touch server state.</p>
 */
class FabricLocalEventTest {

    @Test
    void reportsAnEventThatCannotBeCancelled() {
        LocalEvent event = new FabricLocalEnvironment.FabricLocalEvent(
                "CHAT_MESSAGE", "Steve", null);

        assertEquals("CHAT_MESSAGE", event.info().get("type"));
        assertEquals(false, event.info().get("cancellable"));
        assertEquals(false, event.info().get("cancelled"));
        assertEquals("Steve", event.info().get("player"));

        LocalActionException failure = assertThrows(LocalActionException.class,
                event::cancel);
        assertEquals(LocalActionException.Kind.UNSUPPORTED, failure.kind());
    }

    @Test
    void runsTheCancellationHookWhenTheEventIsCancellable() {
        AtomicBoolean cancelled = new AtomicBoolean();
        LocalEvent event = new FabricLocalEnvironment.FabricLocalEvent(
                "CUSTOM_EVENT", null, () -> cancelled.set(true));

        assertEquals(true, event.info().get("cancellable"));
        assertFalse(event.info().containsKey("player"));

        event.cancel();

        assertTrue(cancelled.get());
    }
}
