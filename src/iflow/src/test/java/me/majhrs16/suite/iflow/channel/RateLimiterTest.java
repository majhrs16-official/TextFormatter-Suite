package me.majhrs16.suite.iflow.channel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static RateLimiter limiter(AtomicLong now) {
        return new RateLimiter(now::get);
    }

    @Test
    void admitsUpToCapacityPerWindowPerKey() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(now);
        int capacity = 3;

        assertTrue(limiter.tryAcquire("chat|Steve", capacity));
        assertTrue(limiter.tryAcquire("chat|Steve", capacity));
        assertTrue(limiter.tryAcquire("chat|Steve", capacity));
        assertFalse(limiter.tryAcquire("chat|Steve", capacity));
    }

    @Test
    void keysAreIndependent() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(now);
        int capacity = 1;

        assertTrue(limiter.tryAcquire("chat|Steve", capacity));
        assertTrue(limiter.tryAcquire("chat|Alex", capacity));
        assertFalse(limiter.tryAcquire("chat|Steve", capacity));
    }

    @Test
    void refillsAfterFullWindow() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(now);
        int capacity = 1;

        assertTrue(limiter.tryAcquire("chat|Steve", capacity));
        now.addAndGet(limiter.nanosUntilNextWindow());
        assertTrue(limiter.tryAcquire("chat|Steve", capacity));
    }

    @Test
    void doesNotRefillBeforeWindowElapses() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(now);
        int capacity = 1;

        assertTrue(limiter.tryAcquire("chat|Steve", capacity));
        now.addAndGet(limiter.nanosUntilNextWindow() / 2);
        assertFalse(limiter.tryAcquire("chat|Steve", capacity));
    }
}