package me.majhrs16.suite.api.runtime;

import java.util.function.Consumer;

/**
 * Module-scoped publish/subscribe bus for neutral runtime events.
 *
 * <p>The bus is how modules react to messages and lifecycle events without
 * importing each other. Subscriptions are owned by the subscribing module and
 * are removed when that module stops.</p>
 *
 * <p>Listeners must not assume a particular thread. A failing listener must not
 * prevent other listeners from receiving the event; the implementation decides
 * how to report the failure but must not swallow it silently.</p>
 */
public interface EventBus {

    /**
     * Subscribes {@code listener} to events assignable to {@code eventType}.
     *
     * @param eventType event class used for dispatch
     * @param listener  callback invoked for each matching event
     * @param <E>       event type
     * @return a handle whose {@link Subscription#close()} unsubscribes;
     *         closing twice is a no-op
     */
    <E> Subscription subscribe(Class<E> eventType, Consumer<E> listener);

    /**
     * Publishes {@code event} to every matching subscriber.
     *
     * @param event event instance; its runtime class selects the subscribers
     */
    void publish(Object event);

    /** Handle returned by {@link #subscribe(Class, Consumer)}. */
    interface Subscription extends AutoCloseable {

        /** Removes the subscription. Idempotent. */
        void unsubscribe();

        /** @return {@code true} while the subscription receives events. */
        boolean isActive();

        @Override
        default void close() {
            unsubscribe();
        }
    }
}
