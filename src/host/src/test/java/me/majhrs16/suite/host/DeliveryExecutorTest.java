package me.majhrs16.suite.host;

import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.pipeline.DeliverySummary;
import me.majhrs16.suite.api.pipeline.DeliveryUnit;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.render.ComponentRenderedOutput;
import me.majhrs16.suite.api.spi.PluginLogger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryExecutorTest {

    private static final Actor ALEX = new Actor(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Alex",
            Actor.ActorKind.PLAYER, Language.ES, null);

    @Test
    void queuesARateLimitedUnitAndDeliversItAfterTheBackoff() throws Exception {
        RecordingDelivery delivery = new RecordingDelivery();
        DeliveryExecutor executor = new DeliveryExecutor(
                delivery, null, false, NoopLogger.INSTANCE, 4);

        DeliverySummary summary = executor.deliver(envelope(
                unit(new RouteDecision(PolicyTarget.RATE_LIMIT, "busy", 25L, ALEX, ALEX))));

        assertEquals(1, summary.queued());
        assertEquals(0, summary.delivered());
        assertTrue(delivery.await(2, TimeUnit.SECONDS));
        assertEquals(ALEX, delivery.lastRecipient());
        executor.close();
    }

    @Test
    void discardsExplicitlyWhenTheRateLimitQueueIsFull() {
        RecordingDelivery delivery = new RecordingDelivery();
        DeliveryExecutor executor = new DeliveryExecutor(
                delivery, null, false, NoopLogger.INSTANCE, 1);
        RouteDecision slow = new RouteDecision(
                PolicyTarget.RATE_LIMIT, "busy", 60_000L, ALEX, ALEX);

        DeliverySummary summary = executor.deliver(envelope(
                unit(slow), unit(slow)));

        assertEquals(1, summary.queued());
        assertEquals(1, summary.discarded());
        assertEquals(0, summary.delivered());
        assertEquals(0, delivery.count());
        executor.close();
    }

    @Test
    void deliversImmediatelyWhenTheBackoffIsZero() {
        RecordingDelivery delivery = new RecordingDelivery();
        DeliveryExecutor executor = new DeliveryExecutor(
                delivery, null, false, NoopLogger.INSTANCE, 1);

        DeliverySummary summary = executor.deliver(envelope(
                unit(new RouteDecision(PolicyTarget.RATE_LIMIT, "busy", 0L, ALEX, ALEX))));

        assertEquals(1, summary.delivered());
        assertEquals(0, summary.queued());
        assertEquals(1, delivery.count());
        executor.close();
    }

    private static DeliveryEnvelope envelope(DeliveryUnit... units) {
        return new DeliveryEnvelope(Message.builder().text("hola").build(), List.of(units));
    }

    private static DeliveryUnit unit(RouteDecision decision) {
        Component text = Component.text("hola");
        return new DeliveryUnit(
                ALEX, decision, Message.builder().text("hola").build(),
                new ComponentRenderedOutput(text, "hola"));
    }

    private static final class RecordingDelivery implements ChatDelivery {

        private final CountDownLatch delivered = new CountDownLatch(1);
        private final AtomicReference<Actor> recipient = new AtomicReference<>();
        private volatile int count;

        @Override
        public void deliver(Actor target, Component rendered, Message original) {
            count++;
            recipient.set(target);
            delivered.countDown();
        }

        @Override
        public void deliverConsole(Component rendered) {
            throw new AssertionError("unexpected console delivery");
        }

        @Override
        public void playSound(Actor target, SoundSpec sound) {
        }

        @Override
        public boolean hasSound(String soundName) {
            return false;
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return delivered.await(timeout, unit);
        }

        Actor lastRecipient() {
            return recipient.get();
        }

        int count() {
            return count;
        }
    }

    private enum NoopLogger implements PluginLogger {
        INSTANCE;

        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}
