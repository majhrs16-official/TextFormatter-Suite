package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import me.majhrs16.suite.iflow.vm.service.ServiceResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContextTest {

    private SoftMMU createMappedMMU() {
        SoftMMU mmu = new SoftMMU();
        // Map all standard pages
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

    private ExecutionContext createContext(SoftMMU mmu) {
        return new ExecutionContext()
            .executionId("test")
            .fuelBudget(10000)
            .softMMU(mmu)
            .serviceRegistry(new HostServiceRegistry());
    }

    @Test
    void initializeResetsAllRegisters() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.registers().set(RegisterFile.R0, 0x1234);
        ctx.registers().set(RegisterFile.R1, 0x5678);
        ctx.initialize();
        for (int i = 0; i < RegisterFile.REG_COUNT; i++) {
            if (RegisterFile.isGPR(i)) {
                assertEquals(0, ctx.registers().get(i), "GPR R" + i + " should be 0 after initialize");
            }
        }
    }

    @Test
    void initializeSetsStackPointer() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        assertEquals(0x6000, ctx.registers().get(RegisterFile.SP));
        assertEquals(0x6000, ctx.registers().get(RegisterFile.FP));
    }

    @Test
    void initializeClearsFlags() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.registers().setFlag(RegisterFile.FLAG_Z, true);
        ctx.registers().setFlag(RegisterFile.FLAG_N, true);
        ctx.registers().setFlag(RegisterFile.FLAG_C, true);
        ctx.registers().setFlag(RegisterFile.FLAG_V, true);
        ctx.initialize();
        assertFalse(ctx.registers().getZ());
        assertFalse(ctx.registers().getN());
        assertFalse(ctx.registers().getC());
        assertFalse(ctx.registers().getV());
    }

    @Test
    void initializeClearsPropertyCacheAndJournal() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.cacheProperty("key", "value");
        ctx.journal().record(new ModificationJournal.Entry(ModificationJournal.OpType.SET_TEXT, "test", "detail"));
        ctx.initialize();
        assertNull(ctx.getCachedProperty("key"));
        assertEquals(0, ctx.journal().size());
    }

    @Test
    void initializeResetsFuel() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();  // Initialize first to set up fuel budget
        ctx.consumeFuel(5000);
        ctx.initialize();  // Re-initialize should reset fuel
        assertEquals(10000, ctx.fuelRemaining());
    }

    @Test
    void initializeClearsIOLBuffers() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        // Write to I/O buffers
        ctx.softMMU().writeByte(ctx.inputBufferVAddr(), 0xAA);
        ctx.softMMU().writeByte(ctx.outputBufferVAddr(), 0xBB);
        // Re-initialize
        ctx.initialize();
        assertEquals(0, ctx.softMMU().readByte(ctx.inputBufferVAddr()));
        assertEquals(0, ctx.softMMU().readByte(ctx.outputBufferVAddr()));
    }

    @Test
    void consumeFuelReducesRemaining() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.consumeFuel(100);
        assertEquals(9900, ctx.fuelRemaining());
    }

    @Test
    void consumeFuelThrowsWhenExhausted() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.consumeFuel(10000);
        assertThrows(VmException.class, () -> ctx.consumeFuel(1));
    }

    @Test
    void fuelBudgetReArmsRemainingAfterInitialize() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();       // arms the configured budget (10000)
        ctx.consumeFuel(5000);  // remaining = 5000
        // Setting the budget after initialize() must take effect immediately:
        // it used to leave fuelRemaining untouched (regression that made
        // VmInterpreterTest.outOfFuelThrows run 10000 cycles instead of 100).
        ctx.fuelBudget(100);
        assertEquals(100, ctx.fuelBudget());
        assertEquals(100, ctx.fuelRemaining());
        assertThrows(VmException.class, () -> ctx.consumeFuel(101));
    }

    @Test
    void consumeHostFuelSumsBaseAndVariable() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.consumeHostFuel(10, 5);
        assertEquals(9985, ctx.fuelRemaining());
    }

    @Test
    void callDepthTracking() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        assertEquals(0, ctx.callDepth());
        ctx.enterCall(0x6000);
        assertEquals(1, ctx.callDepth());
        ctx.enterCall(0x6000);
        assertEquals(2, ctx.callDepth());
        ctx.exitCall();
        assertEquals(1, ctx.callDepth());
        ctx.exitCall();
        assertEquals(0, ctx.callDepth());
    }

    @Test
    void callDepthLimitThrows() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu).maxCallDepth(2);
        ctx.initialize();
        ctx.enterCall(0x6000);
        ctx.enterCall(0x6000);
        assertThrows(VmException.class, () -> ctx.enterCall(0x6000));
    }

    @Test
    void propertyCacheOperations() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.cacheProperty("key1", "value1");
        assertEquals("value1", ctx.getCachedProperty("key1"));
        ctx.invalidateProperty("key1");
        assertNull(ctx.getCachedProperty("key1"));
        ctx.clearPropertyCache();
        ctx.cacheProperty("key2", "value2");
        ctx.clearPropertyCache();
        assertNull(ctx.getCachedProperty("key2"));
    }

    @Test
    void journalOperations() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.journal().record(new ModificationJournal.Entry(ModificationJournal.OpType.SET_TEXT, "hello", "detail"));
        assertEquals(1, ctx.journal().size());
        ctx.journal().clear();
        assertEquals(0, ctx.journal().size());
    }

    @Test
    void cancellation() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        assertFalse(ctx.isCancelled());
        ctx.cancel();
        assertTrue(ctx.isCancelled());
        assertThrows(VmException.class, () -> ctx.checkCancelled());
    }

    @Test
    void fatalErrorTracking() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        assertFalse(ctx.hasFatalError());
        VmException ex = new VmException(VmException.Code.MEMORY_FAULT, "test");
        ctx.setFatalError(ex);
        assertTrue(ctx.hasFatalError());
        assertEquals(ex, ctx.fatalError());
    }

    @Test
    void ioBufferAddresses() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        assertEquals(0x6000, ctx.inputBufferVAddr());
        assertEquals(0x7000, ctx.outputBufferVAddr());
        assertEquals(4096, ctx.ioBufferSize());
    }

    @Test
    void pageTableAccess() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.setPageTable(mmu.getPageTable());
        assertSame(mmu.getPageTable(), ctx.pageTable());
    }

    @Test
    void callHostInvokesService() {
        SoftMMU mmu = createMappedMMU();
        HostServiceRegistry hsr = new HostServiceRegistry()
            .propertyReader((scope, propId) -> "test-value");
        ExecutionContext ctx = new ExecutionContext()
            .executionId("test")
            .fuelBudget(10000)
            .softMMU(mmu)
            .serviceRegistry(hsr)
            .allowedCapabilities(new String[]{"property.read"});
        ctx.initialize();
        ServiceResult result = ctx.callHost(HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 0, 10, 0);
        assertNotNull(result);
    }

    @Test
    void elapsedNanosIncreases() throws InterruptedException {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        long start = ctx.elapsedNanos();
        Thread.sleep(1);
        long end = ctx.elapsedNanos();
        assertTrue(end > start);
    }

    @Test
    void resetAliasForInitialize() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.registers().set(RegisterFile.R0, 0x1234);
        ctx.reset();
        assertEquals(0, ctx.registers().get(RegisterFile.R0));
    }

    @Test
    void gettersReturnConfiguredValues() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = new ExecutionContext()
            .executionId("exec-123")
            .programId("prog-456")
            .messageId("msg-789")
            .actorId("player1")
            .fuelBudget(50000)
            .maxCallDepth(32)
            .maxStackSize(8192)
            .softMMU(mmu)
            .serviceRegistry(new HostServiceRegistry());
        ctx.initialize();
        assertEquals("exec-123", ctx.executionId());
        assertEquals("prog-456", ctx.programId());
        assertEquals("msg-789", ctx.messageId());
        assertEquals("player1", ctx.actorId());
        assertEquals(50000, ctx.fuelBudget());
        assertEquals(32, ctx.maxCallDepth());
        assertEquals(8192, ctx.maxStackSize());
    }

    @Test
    void stackOverflowOnCallDetected() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu).maxStackSize(2);
        ctx.initialize();
        // Stack starts at 0x6000, max 2 bytes means 1 word
        ctx.registers().set(RegisterFile.SP, 0x6000);
        ctx.enterCall(0x6000); // First call: SP=0x6000, check passes
        // Simulate interpreter decrementing SP after first call
        ctx.registers().set(RegisterFile.SP, 0x5FFE);
        assertThrows(VmException.class, () -> ctx.enterCall(0x6000)); // Second call: SP=0x5FFE, would exceed 2 bytes
    }

    @Test
    void exitCallRestoresFramePointer() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        ctx.enterCall(0x6000);
        ctx.enterCall(0x5FFE);
        assertEquals(0x5FFE, ctx.exitCall());
        assertEquals(0x6000, ctx.exitCall());
        assertEquals(0, ctx.callDepth());
    }

    @Test
    void exitCallWithoutEnterThrows() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        assertThrows(VmException.class, () -> ctx.exitCall());
    }
}