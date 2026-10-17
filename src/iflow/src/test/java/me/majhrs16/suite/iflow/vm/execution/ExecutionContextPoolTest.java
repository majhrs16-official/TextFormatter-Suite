package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class ExecutionContextPoolTest {

    @Test
    void acquireCreatesNewContextWhenEmpty() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
        ExecutionContext ctx = pool.acquire();
        assertNotNull(ctx);
        assertEquals(1, pool.created());
        assertEquals(1, pool.acquired());
        assertEquals(0, pool.available());
    }

    @Test
    void releaseReturnsContextToPool() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
        ExecutionContext ctx = pool.acquire();
        pool.release(ctx);
        assertEquals(1, pool.available());
        assertEquals(1, pool.released());
    }

    @Test
    void releasedContextIsReused() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
        ExecutionContext ctx1 = pool.acquire();
        ctx1.registers().set(0, 0x1234);
        pool.release(ctx1);
        ExecutionContext ctx2 = pool.acquire();
        assertSame(ctx1, ctx2);
        assertEquals(2, pool.acquired());
        assertEquals(1, pool.created());
    }

    @Test
    void releasedContextIsReset() {
        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> ctx.fuelBudget(10000));
        ExecutionContext ctx1 = pool.acquire();
        ctx1.initialize();
        ctx1.registers().set(0, 0x1234);
        ctx1.consumeFuel(5000);
        ctx1.cacheProperty("key", "value");
        pool.release(ctx1);
        ExecutionContext ctx2 = pool.acquire();
        assertEquals(0, ctx2.registers().get(0));
        assertEquals(ctx2.fuelBudget(), ctx2.fuelRemaining());
        assertNull(ctx2.getCachedProperty("key"));
    }

    @Test
    void committedJournalDoesNotBreakPooling() {
        // Production path: the validator commits the journal after a successful
        // route, then release() resets the context. clear() used to throw here,
        // silently discarding the context so the pool never reused anything.
        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
        ExecutionContext ctx1 = pool.acquire();
        ctx1.initialize();
        ctx1.journal().setText("edited");
        ctx1.journal().commit();
        pool.release(ctx1);

        assertEquals(1, pool.available());
        assertEquals(1, pool.created());

        ExecutionContext ctx2 = pool.acquire();
        assertSame(ctx1, ctx2);
        assertTrue(ctx2.journal().isEmpty());
        assertFalse(ctx2.journal().isCommitted());
    }

    @Test
    void configuratorReappliedOnRelease() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> ctx.fuelBudget(5000));
        ExecutionContext ctx1 = pool.acquire();
        pool.release(ctx1);
        ExecutionContext ctx2 = pool.acquire();
        assertEquals(5000, ctx2.fuelBudget());
    }

    @Test
    void poolRespectsMaxSize() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> {}, 2);
        ExecutionContext ctx1 = pool.acquire();
        ExecutionContext ctx2 = pool.acquire();
        pool.release(ctx1);
        pool.release(ctx2);
        ExecutionContext ctx3 = pool.acquire();
        pool.release(ctx3);
        assertEquals(2, pool.maxSize());
        assertEquals(2, pool.created());
    }

    @Test
    void poolFullDiscardsExtraContexts() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> {}, 1);
        ExecutionContext ctx1 = pool.acquire();
        pool.release(ctx1);
        ExecutionContext ctx2 = pool.acquire();
        pool.release(ctx2);
        ExecutionContext ctx3 = pool.acquire();
        pool.release(ctx3);
        // Third context should be created but then discarded when released
        assertEquals(1, pool.maxSize());
    }

    @Test
    void closedPoolThrowsOnAcquire() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> {});
        pool.close();
        assertThrows(IllegalStateException.class, () -> pool.acquire());
    }

    @Test
    void closeClearsPool() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> {});
        ExecutionContext ctx = pool.acquire();
        pool.release(ctx);
        pool.close();
        assertEquals(0, pool.available());
    }

    @Test
    void releaseNullIsNoOp() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> {});
        pool.release(null);
        assertEquals(0, pool.available());
    }

    @Test
    void statisticsTrackedCorrectly() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> {}, 10);
        assertEquals(0, pool.acquired());
        assertEquals(0, pool.released());
        ExecutionContext ctx = pool.acquire();
        assertEquals(1, pool.acquired());
        assertEquals(0, pool.released());
        pool.release(ctx);
        assertEquals(1, pool.released());
    }

    @Test
    void discardOnResetFailure() {

        AtomicInteger callCount = new AtomicInteger(0);
        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> {
            if (callCount.incrementAndGet() == 2) { // Throw on second call (release)
                throw new IllegalStateException("Config failed");
            }
        }, 5);
        ExecutionContext ctx = pool.acquire();
        pool.release(ctx);
        // Context should be discarded, pool size reduced
        assertEquals(0, pool.created());
    }

    @Test
    void contextIsolationBetweenAcquisitions() {

        ExecutionContextPool pool = new ExecutionContextPool(new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
        ExecutionContext ctx1 = pool.acquire();
        ctx1.initialize();
        ctx1.registers().set(0, 0x1111);
        ctx1.consumeFuel(500);
        pool.release(ctx1);
        ExecutionContext ctx2 = pool.acquire();
        // ctx2 should have fresh state
        assertEquals(0, ctx2.registers().get(0));
        assertEquals(1000, ctx2.fuelRemaining());
    }
}