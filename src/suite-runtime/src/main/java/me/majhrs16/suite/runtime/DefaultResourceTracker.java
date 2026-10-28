package me.majhrs16.suite.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Resource tracker for one module activation.
 *
 * <p>Cleanups run in reverse registration order. A failing cleanup is logged
 * and does not prevent the remaining resources from being released.</p>
 */
public final class DefaultResourceTracker implements ResourceTracker {

    private final PluginLogger logger;
    private final List<Runnable> cleanups = new ArrayList<>();
    private final AtomicBoolean released = new AtomicBoolean();

    public DefaultResourceTracker(PluginLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public <T extends AutoCloseable> T track(T resource) {
        Objects.requireNonNull(resource, "resource");
        cleanups.add(() -> {
            try {
                resource.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close " + resource.getClass().getName(), e);
            }
        });
        return resource;
    }

    @Override
    public Registration onRelease(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        cleanups.add(cleanup);
        AtomicBoolean removed = new AtomicBoolean();
        return () -> {
            if (removed.compareAndSet(false, true)) {
                cleanups.remove(cleanup);
            }
        };
    }

    @Override
    public void releaseAll() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> pending = new ArrayList<>(cleanups);
        cleanups.clear();
        for (int i = pending.size() - 1; i >= 0; i--) {
            try {
                pending.get(i).run();
            } catch (RuntimeException failure) {
                logger.error("Resource cleanup failed", failure);
            }
        }
    }

    @Override
    public boolean isReleased() {
        return released.get();
    }
}
