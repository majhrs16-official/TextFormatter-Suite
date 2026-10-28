package me.majhrs16.suite.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Event bus shared by every active module.
 *
 * <p>Subscriptions are owned by the module that created them, so deactivation
 * cannot leave a listener pointing at a closed classloader. A failing listener
 * is reported through the logger and never prevents the remaining listeners
 * from receiving the event.</p>
 */
public final class RuntimeEventBus implements EventBus {

    private final PluginLogger logger;
    private final List<Entry> subscriptions = new CopyOnWriteArrayList<>();

    public RuntimeEventBus(PluginLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public <E> Subscription subscribe(Class<E> eventType, Consumer<E> listener) {
        return subscribe("runtime", eventType, listener);
    }

    /**
     * Subscribes a listener on behalf of {@code owner}.
     *
     * @param owner     module id
     * @param eventType event type
     * @param listener  callback
     * @param <E>       event type
     * @return subscription handle
     */
    public <E> Subscription subscribe(String owner, Class<E> eventType, Consumer<E> listener) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        Entry entry = new Entry(owner, eventType, cast(listener));
        subscriptions.add(entry);
        return entry;
    }

    @Override
    public void publish(Object event) {
        Objects.requireNonNull(event, "event");
        for (Entry entry : subscriptions) {
            if (!entry.isActive() || !entry.eventType.isInstance(event)) {
                continue;
            }
            try {
                entry.listener.accept(event);
            } catch (RuntimeException failure) {
                logger.error("Event listener for " + entry.eventType.getName()
                        + " failed while handling " + event.getClass().getName(), failure);
            }
        }
    }

    /**
     * Removes every subscription owned by {@code owner}.
     *
     * @param owner module id
     */
    public void removeOwner(String owner) {
        List<Entry> removed = new ArrayList<>();
        for (Entry entry : subscriptions) {
            if (entry.owner.equals(owner)) {
                entry.unsubscribe();
                removed.add(entry);
            }
        }
        subscriptions.removeAll(removed);
    }

    /**
     * @param owner module id
     * @return the module-scoped view of this bus
     */
    public EventBus scope(String owner) {
        return new ScopedEventBus(owner);
    }

    /** Removes every subscription. */
    public void close() {
        subscriptions.forEach(Entry::unsubscribe);
        subscriptions.clear();
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> cast(Consumer<?> listener) {
        return (Consumer<Object>) listener;
    }

    private final class Entry implements Subscription {

        private final String owner;
        private final Class<?> eventType;
        private final Consumer<Object> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Entry(String owner, Class<?> eventType, Consumer<Object> listener) {
            this.owner = owner;
            this.eventType = eventType;
            this.listener = listener;
        }

        @Override
        public void unsubscribe() {
            active.set(false);
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }

    private final class ScopedEventBus implements EventBus {

        private final String owner;

        private ScopedEventBus(String owner) {
            this.owner = owner;
        }

        @Override
        public <E> Subscription subscribe(Class<E> eventType, Consumer<E> listener) {
            return RuntimeEventBus.this.subscribe(owner, eventType, listener);
        }

        @Override
        public void publish(Object event) {
            RuntimeEventBus.this.publish(event);
        }
    }
}
