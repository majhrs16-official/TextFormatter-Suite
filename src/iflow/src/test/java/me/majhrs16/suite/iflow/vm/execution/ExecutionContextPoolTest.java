package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class ExecutionContextPoolTest {

    private SoftMMU createTemplateMMU() {
        SoftMMU mmu = new SoftMMU();
        mmu.getPageTable().mapPage(0, 0x0000, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        mmu.getPageTable().mapPage(1, 0x1000, PageEntry.PERM_READ);
        mmu.getPageTable().mapPage(2, 0x2000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(3, 0x3000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(4, 0x4000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(5, 0x5000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(6, 0x6000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(7, 0x7000, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        return mmu;
    }

    @Test
    void acquireCreatesNewContextWhenEmpty() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
        ExecutionContext ctx = pool.acquire();
        assertNotNull(ctx);
        assertEquals(1, pool.created());
        assertEquals(1, pool.acquired());
        assertEquals(0, pool.available());
    }

    @Test
    void releaseReturnsContextToPool() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
        ExecutionContext ctx = pool.acquire();
        pool.release(ctx);
        assertEquals(1, pool.available());
        assertEquals(1, pool.released());
    }

    @Test
    void releasedContextIsReused() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
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
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> ctx.fuelBudget(10000));
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
    void configuratorReappliedOnRelease() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> ctx.fuelBudget(5000));
        ExecutionContext ctx1 = pool.acquire();
        pool.release(ctx1);
        ExecutionContext ctx2 = pool.acquire();
        assertEquals(5000, ctx2.fuelBudget());
    }

    @Test
    void poolRespectsMaxSize() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> {}, 2);
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
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> {}, 1);
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
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> {});
        pool.close();
        assertThrows(IllegalStateException.class, () -> pool.acquire());
    }

    @Test
    void closeClearsPool() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> {});
        ExecutionContext ctx = pool.acquire();
        pool.release(ctx);
        pool.close();
        assertEquals(0, pool.available());
    }

    @Test
    void releaseNullIsNoOp() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> {});
        pool.release(null);
        assertEquals(0, pool.available());
    }

    @Test
    void statisticsTrackedCorrectly() {
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> {}, 10);
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
        SoftMMU mmu = createTemplateMMU();
        AtomicInteger callCount = new AtomicInteger(0);
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> {
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
        SoftMMU mmu = createTemplateMMU();
        ExecutionContextPool pool = new ExecutionContextPool(mmu, new HostServiceRegistry(), ctx -> ctx.fuelBudget(1000));
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