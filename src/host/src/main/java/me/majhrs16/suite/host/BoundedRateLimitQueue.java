package me.majhrs16.suite.host;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded queue of rate-limited deliveries.
 *
 * <p>A routing decision may return {@code RATE_LIMIT} with a backoff; the
 * delivery executor waits that delay without blocking the dispatcher. The
 * number of pending deliveries is capped so a burst cannot grow the host heap
 * without limit. A full queue is an explicit discard: the caller receives a
 * {@link DeliveryResult.Kind#DISCARDED} result and the drop is counted, never
 * reported as a successful or queued delivery.</p>
 *
 * <p>The capacity is a host safety limit, not a VM-memory budget. The default
 * follows the conservative text-UUID budget of {@code iflow-16} (24,000 / 32 =
 * 750); each pending entry still retains its rendered component, so the actual
 * heap cost depends on message size. A VM-side queue would store 8-10 byte
 * handles plus metadata, not full UUIDs; the budget and its limits are
 * documented in {@code src/iflow-16/README.md}.</p>
 */
final class BoundedRateLimitQueue {

    /** Default capacity aligned with the {@code iflow-16} per-second ceiling. */
    static final int DEFAULT_CAPACITY = 750;

    private final ScheduledExecutorService scheduler;
    private final Semaphore permits;
    private final int capacity;
    private final AtomicLong discarded = new AtomicLong();

    BoundedRateLimitQueue(ScheduledExecutorService scheduler, int capacity) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.permits = new Semaphore(capacity);
    }

    /**
     * Schedules {@code task} after {@code delayMillis}.
     *
     * @return {@code true} when the task was accepted; {@code false} when the
     *         queue is full or the scheduler is shut down
     */
    boolean offer(long delayMillis, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (!permits.tryAcquire()) {
            discarded.incrementAndGet();
            return false;
        }
        try {
            scheduler.schedule(() -> {
                try {
                    task.run();
                } finally {
                    permits.release();
                }
            }, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException rejected) {
            permits.release();
            discarded.incrementAndGet();
            return false;
        }
    }

    /** @return pending or running deliveries */
    int pending() {
        return capacity - permits.availablePermits();
    }

    /** @return configured maximum number of pending deliveries */
    int capacity() {
        return capacity;
    }

    /** @return deliveries rejected because the queue was full or closed */
    long discarded() {
        return discarded.get();
    }
}
