package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of {@link SyncBus}.
 * <p>
 * Thread-safe: all public methods can be called concurrently.
 * Sinks are stored in a concurrent map; iteration uses a snapshot for safety.
 * </p>
 */
public final class DefaultSyncBus implements SyncBus {

    private final Map<String, SyncSink> sinks = new ConcurrentHashMap<>();
    private final Set<SyncSink> sinkSnapshot = new CopyOnWriteArraySet<>();
    private volatile SyncListener inboundListener;
    private final PluginLogger logger;
    private final AtomicInteger started = new AtomicInteger(0);

    public DefaultSyncBus(PluginLogger logger) {
        this.logger = logger;
    }

    @Override
    public void register(SyncSink sink) {
        String name = sink.name();
        SyncSink previous = sinks.putIfAbsent(name, sink);
        if (previous != null) {
            throw new IllegalStateException("Sink with name '" + name + "' already registered: " + previous.getClass().getName());
        }
        sink.setListener(new SyncListener() {
            @Override
            public void onMessage(SyncSink s, Message message) {
                onInboundMessage(s, message);
            }

            @Override
            public void onDisconnect(SyncSink s, String reason) {
                logger.warn("SyncBus: sink '" + s.name() + "' disconnected: " + reason);
            }
        });
        sinkSnapshot.add(sink);
        logger.debug("SyncBus: registered sink '" + name + "' (" + sink.getClass().getSimpleName() + ")");
    }

    @Override
    public boolean unregister(String name) {
        SyncSink removed = sinks.remove(name);
        if (removed != null) {
            sinkSnapshot.remove(removed);
            try {
                removed.stop();
            } catch (Exception e) {
                logger.warn("SyncBus: error stopping sink '" + name + "' during unregister: " + e.getMessage());
            }
            logger.debug("SyncBus: unregistered sink '" + name + "'");
            return true;
        }
        return false;
    }

    @Override
    public Collection<SyncSink> sinks() {
        return Collections.unmodifiableCollection(sinkSnapshot);
    }

    @Override
    public Set<String> sinkNames() {
        return Collections.unmodifiableSet(new HashSet<>(sinks.keySet()));
    }

    @Override
    public int broadcast(Message message) {
        if (sinks.isEmpty()) {
            logger.debug("SyncBus: broadcast called but no sinks registered");
            return 0;
        }

        int successCount = 0;
        for (SyncSink sink : sinkSnapshot) {
            try {
                sink.send(message);
                successCount++;
            } catch (Exception e) {
                logger.error("SyncBus: sink '" + sink.name() + "' failed to send message: " + e.getMessage(), e);
            }
        }
        return successCount;
    }

    @Override
    public boolean sendTo(String sinkName, Message message) {
        SyncSink sink = sinks.get(sinkName);
        if (sink == null) {
            logger.warn("SyncBus: sink '" + sinkName + "' not found for directed send");
            return false;
        }
        try {
            sink.send(message);
            return true;
        } catch (Exception e) {
            logger.error("SyncBus: sink '" + sinkName + "' failed to send message: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void setInboundListener(SyncListener listener) {
        this.inboundListener = listener;
        logger.debug("SyncBus: inbound listener " + (listener != null ? "set" : "cleared"));
    }

    @Override
    public SyncListener getInboundListener() {
        return inboundListener;
    }

    @Override
    public void start() throws Exception {
        if (started.getAndSet(1) == 1) {
            logger.debug("SyncBus: already started");
            return;
        }

        for (SyncSink sink : sinkSnapshot) {
            try {
                sink.start();
                logger.info("SyncBus: started sink '" + sink.name() + "'");
            } catch (Exception e) {
                logger.error("SyncBus: failed to start sink '" + sink.name() + "': " + e.getMessage(), e);
                throw e;
            }
        }
    }

    @Override
    public void stop() {
        if (started.getAndSet(0) == 0) {
            logger.debug("SyncBus: already stopped");
            return;
        }

        for (SyncSink sink : sinkSnapshot) {
            try {
                sink.stop();
                logger.info("SyncBus: stopped sink '" + sink.name() + "'");
            } catch (Exception e) {
                logger.warn("SyncBus: error stopping sink '" + sink.name() + "': " + e.getMessage());
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return sinks.isEmpty();
    }

    @Override
    public void close() {
        stop();
    }

    private void onInboundMessage(SyncSink sink, Message message) {
        SyncListener listener = inboundListener;
        if (listener != null) {
            try {
                listener.onMessage(sink, message);
            } catch (Exception e) {
                logger.error("SyncBus: inbound listener error for sink '" + sink.name() + "': " + e.getMessage(), e);
            }
        }
    }
}