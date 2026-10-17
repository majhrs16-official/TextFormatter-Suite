package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of reusable ExecutionContext instances.
 * <p>
 * Contexts are expensive to create (allocate registers, etc.) so we pool them.
 * Each context is fully reset before reuse to prevent state leakage.
 * The SoftMMU is set by the caller (VmPolicyEngine) from the current loaded program.
 * {@code maxSize} bounds the number of <em>retained</em> (idle) contexts so the
 * pool cannot grow without bound; {@link #acquire()} never blocks the message
 * path, so it may transiently create more contexts than {@code maxSize} under a
 * burst, and {@link #release(ExecutionContext)} discards the surplus.
 * Thread-safe for concurrent access.
 * </p>
 */
public final class ExecutionContextPool implements AutoCloseable {

    private final Queue<ExecutionContext> available = new ConcurrentLinkedQueue<>();
    private final int maxSize;
    private final HostServiceRegistry serviceRegistry;
    private final java.util.function.Consumer<ExecutionContext> configurator;
    private final AtomicInteger created = new AtomicInteger(0);
    private final AtomicInteger acquired = new AtomicInteger(0);
    private final AtomicInteger released = new AtomicInteger(0);
    private volatile boolean closed = false;

    public ExecutionContextPool(HostServiceRegistry serviceRegistry,
                                 java.util.function.Consumer<ExecutionContext> configurator) {
        this(serviceRegistry, configurator, 32);
    }

    public ExecutionContextPool(HostServiceRegistry serviceRegistry,
                                 java.util.function.Consumer<ExecutionContext> configurator,
                                 int maxSize) {
        this.serviceRegistry = serviceRegistry;
        this.configurator = configurator;
        this.maxSize = maxSize;
    }

    /** Acquires a context from the pool or creates a new one. */
    public ExecutionContext acquire() {
        if (closed) {
            throw new IllegalStateException("Pool is closed");
        }

        ExecutionContext ctx = available.poll();
        if (ctx == null) {
            // No idle context: create one. The pool must never block the message
            // path, so maxSize bounds the contexts we *retain* (see release()),
            // not the number that can exist concurrently.
            ctx = new ExecutionContext();
            created.incrementAndGet();
        }

        // Apply configuration (fuel budget, etc.) to ensure clean state
        configurator.accept(ctx);

        acquired.incrementAndGet();
        return ctx;
    }

    /** Releases a context back to the pool. */
    public void release(ExecutionContext ctx) {
        if (ctx == null) return;

        // Reset context for reuse
        try {
            ctx.reset();
            // Re-apply configuration
            configurator.accept(ctx);
        } catch (Exception e) {
            // If reset fails, discard this context (don't return to pool)
            created.decrementAndGet();
            return;
        }

        // Retain-or-discard must be atomic with the size check, otherwise two
        // concurrent releases can both see room and push available() above maxSize.
        synchronized (this) {
            if (!closed && available.size() < maxSize) {
                available.offer(ctx);
                released.incrementAndGet();
            } else {
                // Pool full (or closed), let it be GC'd
                created.decrementAndGet();
            }
        }
    }

    public int available() { return available.size(); }
    public int created() { return created.get(); }
    public int acquired() { return acquired.get(); }
    public int released() { return released.get(); }
    public int maxSize() { return maxSize; }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            int dropped = available.size();
            available.clear();
            // Contexts we drop are no longer owned, so they must stop counting.
            if (dropped > 0) created.addAndGet(-dropped);
        }
    }
}