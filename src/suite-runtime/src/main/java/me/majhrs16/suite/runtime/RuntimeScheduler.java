package me.majhrs16.suite.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Scheduler shared by the runtime, scoped per module.
 *
 * <p>Every task records its owner. Deactivating a module cancels its tasks
 * before the classloader closes; closing the runtime stops the executor so no
 * non-daemon thread outlives the suite.</p>
 */
public final class RuntimeScheduler implements AutoCloseable {

    private final ScheduledExecutorService executor;
    private final PluginLogger logger;
    private final Map<String, Set<Handle>> tasksByOwner = new ConcurrentHashMap<>();

    public RuntimeScheduler(PluginLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "suite-runtime-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * @param owner module id
     * @return the module-scoped view of this scheduler
     */
    public Scheduler scope(String owner) {
        return new ScopedScheduler(owner);
    }

    /**
     * Cancels every task owned by {@code owner}.
     *
     * @param owner module id
     */
    public void cancelOwner(String owner) {
        Set<Handle> handles = tasksByOwner.remove(owner);
        if (handles == null) {
            return;
        }
        for (Handle handle : new ArrayList<>(handles)) {
            handle.cancel();
        }
    }

    @Override
    public void close() {
        for (String owner : new ArrayList<>(tasksByOwner.keySet())) {
            cancelOwner(owner);
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Handle track(String owner, ScheduledFuture<?> future) {
        Handle handle = new Handle(owner, future);
        tasksByOwner.computeIfAbsent(owner, ignored -> ConcurrentHashMap.newKeySet()).add(handle);
        return handle;
    }

    private final class ScopedScheduler implements Scheduler {

        private final String owner;

        private ScopedScheduler(String owner) {
            this.owner = owner;
        }

        @Override
        public Cancellable schedule(Runnable task, long delayMillis) {
            Objects.requireNonNull(task, "task");
            return track(owner, executor.schedule(guarded(task), Math.max(0, delayMillis), TimeUnit.MILLISECONDS));
        }

        @Override
        public Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
            Objects.requireNonNull(task, "task");
            if (periodMillis <= 0) {
                throw new IllegalArgumentException("periodMillis must be positive");
            }
            return track(owner,
                    executor.scheduleAtFixedRate(
                            guarded(task),
                            Math.max(0, initialDelayMillis),
                            periodMillis,
                            TimeUnit.MILLISECONDS));
        }

        @Override
        public Cancellable execute(Runnable task) {
            Objects.requireNonNull(task, "task");
            return track(owner, executor.schedule(guarded(task), 0, TimeUnit.MILLISECONDS));
        }

        private Runnable guarded(Runnable task) {
            return () -> {
                try {
                    task.run();
                } catch (RuntimeException failure) {
                    logger.error("Scheduled task for module " + owner + " failed", failure);
                }
            };
        }
    }

    private final class Handle implements Scheduler.Cancellable {

        private final String owner;
        private final ScheduledFuture<?> future;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Handle(String owner, ScheduledFuture<?> future) {
            this.owner = owner;
            this.future = future;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                future.cancel(false);
                Set<Handle> handles = tasksByOwner.get(owner);
                if (handles != null) {
                    handles.remove(this);
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
