package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of reusable ExecutionContext instances.
 * <p>
 * Contexts are expensive to create (allocate SoftMMU, etc.) so we pool them.
 * Each context is fully reset before reuse to prevent state leakage.
 * </p>
 */
public final class ExecutionContextPool implements AutoCloseable {

    private final Queue<ExecutionContext> available = new ArrayDeque<>();
    private final int maxSize;
    private final SoftMMU templateSoftMMU;
    private final HostServiceRegistry serviceRegistry;
    private final java.util.function.Consumer<ExecutionContext> configurator;
    private final AtomicInteger created = new AtomicInteger(0);
    private final AtomicInteger acquired = new AtomicInteger(0);
    private final AtomicInteger released = new AtomicInteger(0);
    private volatile boolean closed = false;

    public ExecutionContextPool(SoftMMU templateSoftMMU,
                                 HostServiceRegistry serviceRegistry,
                                 java.util.function.Consumer<ExecutionContext> configurator) {
        this(templateSoftMMU, serviceRegistry, configurator, 32);
    }

    public ExecutionContextPool(SoftMMU templateSoftMMU,
                                 HostServiceRegistry serviceRegistry,
                                 java.util.function.Consumer<ExecutionContext> configurator,
                                 int maxSize) {
        this.templateSoftMMU = templateSoftMMU;
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
            if (created.get() >= maxSize) {
                // Pool exhausted - create anyway but log warning
                // In production, might want to block or throw
            }
            ctx = new ExecutionContext();
            // Create a copy of the template SoftMMU for this context
            ctx.softMMU(new SoftMMU(templateSoftMMU));
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
            // Could log warning here
            created.decrementAndGet();
            return;
        }

        if (available.size() < maxSize) {
            available.offer(ctx);
            released.incrementAndGet();
        } else {
            // Pool full, let it be GC'd
            created.decrementAndGet();
        }
    }

    public int available() { return available.size(); }
    public int created() { return created.get(); }
    public int acquired() { return acquired.get(); }
    public int released() { return released.get(); }
    public int maxSize() { return maxSize; }

    @Override
    public void close() {
        closed = true;
        available.clear();
    }
}