package me.majhrs16.suite.iflow.channel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static RateLimiter limiter(int capacity, AtomicLong now) {
        return new RateLimiter(capacity, now::get);
    }

    @Test
    void admitsUpToCapacityPerWindowPerKey() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(3, now);

        assertTrue(limiter.tryAcquire("chat|Steve"));
        assertTrue(limiter.tryAcquire("chat|Steve"));
        assertTrue(limiter.tryAcquire("chat|Steve"));
        assertFalse(limiter.tryAcquire("chat|Steve"));
    }

    @Test
    void keysAreIndependent() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(1, now);

        assertTrue(limiter.tryAcquire("chat|Steve"));
        assertTrue(limiter.tryAcquire("chat|Alex"));
        assertFalse(limiter.tryAcquire("chat|Steve"));
    }

    @Test
    void refillsAfterFullWindow() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(1, now);

        assertTrue(limiter.tryAcquire("chat|Steve"));
        now.addAndGet(limiter.nanosUntilNextWindow());
        assertTrue(limiter.tryAcquire("chat|Steve"));
    }

    @Test
    void doesNotRefillBeforeWindowElapses() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = limiter(1, now);

        assertTrue(limiter.tryAcquire("chat|Steve"));
        now.addAndGet(limiter.nanosUntilNextWindow() / 2);
        assertFalse(limiter.tryAcquire("chat|Steve"));
    }
}