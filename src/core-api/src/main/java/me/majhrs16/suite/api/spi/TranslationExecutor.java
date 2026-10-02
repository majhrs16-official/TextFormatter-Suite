package me.majhrs16.suite.api.spi;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated executor for translation operations.
 * <p>
 * Replaces unbounded {@code CompletableFuture.supplyAsync()} (which uses
 * {@link ForkJoinPool#commonPool()}) with a controlled executor providing:
 * <ul>
 *   <li>Bounded concurrency (configurable core/max threads)</li>
 *   <li>Bounded queue with rejection policy</li>
 *   <li>Timeout support for individual tasks</li>
 *   <li>Cancellation propagation</li>
 * </ul>
 * </p>
 */
public final class TranslationExecutor implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;
    private final long defaultTimeoutMillis;
    private final TimeUnit defaultTimeoutUnit;

    private TranslationExecutor(Builder builder) {
        this.defaultTimeoutMillis = builder.timeoutMillis;
        this.defaultTimeoutUnit = builder.timeoutUnit;
        this.executor = new ThreadPoolExecutor(
            builder.coreThreads,
            builder.maxThreads,
            builder.keepAliveMillis,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(builder.queueCapacity),
            r -> {
                Thread t = new Thread(r, "translation-worker-" + builder.instanceId.getAndIncrement());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "translation-scheduler-" + builder.instanceId.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submits a translation task with the default timeout.
     *
     * @param task the translation task
     * @param <T>  the result type
     * @return a CompletableFuture that completes with the result or times out
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        return submit(task, defaultTimeoutMillis, defaultTimeoutUnit);
    }

    /**
     * Submits a translation task with a custom timeout.
     *
     * @param task       the translation task
     * @param timeout    the timeout value
     * @param unit       the timeout unit
     * @param <T>        the result type
     * @return a CompletableFuture that completes with the result or times out
     */
    public <T> CompletableFuture<T> submit(Callable<T> task, long timeout, TimeUnit unit) {
        CompletableFuture<T> future = new CompletableFuture<>();

        Future<?> executorFuture = executor.submit(() -> {
            try {
                T result = task.call();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        // Schedule timeout cancellation
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.cancel(true); // Interrupt the running task
                executorFuture.cancel(true); // Cancel the underlying task
                future.completeExceptionally(new TimeoutException("Translation task timed out after " + timeout + " " + unit));
            }
        }, timeout, unit);

        return future;
    }

    /**
     * Submits a runnable task (fire-and-forget with timeout monitoring).
     */
    public void execute(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                // Log or handle as needed
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * @return current queue size
     */
    public int queueSize() {
        return executor.getQueue().size();
    }

    /**
     * @return active thread count
     */
    public int activeCount() {
        return executor.getActiveCount();
    }

    /**
     * @return whether the executor has been shut down
     */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        executor.shutdown();
        scheduler.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Builder for {@link TranslationExecutor}.
     */
    public static class Builder {
        private int coreThreads = 4;
        private int maxThreads = 16;
        private long keepAliveMillis = 60_000;
        private int queueCapacity = 1000;
        private long timeoutMillis = 30_000;
        private TimeUnit timeoutUnit = TimeUnit.MILLISECONDS;
        private static final AtomicInteger instanceId = new AtomicInteger(0);

        public Builder coreThreads(int coreThreads) {
            this.coreThreads = coreThreads;
            return this;
        }

        public Builder maxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
            return this;
        }

        public Builder keepAliveMillis(long keepAliveMillis) {
            this.keepAliveMillis = keepAliveMillis;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder timeout(long timeout, TimeUnit unit) {
            this.timeoutMillis = unit.toMillis(timeout);
            this.timeoutUnit = unit;
            return this;
        }

        public TranslationExecutor build() {
            return new TranslationExecutor(this);
        }
    }

    /**
     * Creates a default TranslationExecutor with sensible defaults.
     */
    public static TranslationExecutor createDefault() {
        return new Builder().build();
    }
}