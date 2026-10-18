package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.PageTable;
import me.majhrs16.suite.iflow.vm.memory.Privilege;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.memory.VmBus;
import me.majhrs16.suite.iflow.vm.memory.VmDmaController;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import me.majhrs16.suite.iflow.vm.service.ServiceResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContextTest {

    private SoftMMU createMappedMMU() {
        SoftMMU mmu = new SoftMMU();
        // Standard layout, derived from the single-source memory map (VmMemoryMap).
        for (int p = 0; p < VmMemoryMap.CODE_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p,
                VmMemoryMap.CODE_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        }
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.CONSTANTS_BASE),
            VmMemoryMap.CONSTANTS_BASE, PageEntry.PERM_READ);
        for (int p = 0; p < VmMemoryMap.DATA_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.DATA_BASE) + p,
                VmMemoryMap.DATA_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        for (int p = 0; p < VmMemoryMap.STACK_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.STACK_BASE) + p,
                VmMemoryMap.STACK_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
            VmMemoryMap.INPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE),
            VmMemoryMap.OUTPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
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
        assertEquals(0x7000, ctx.registers().get(RegisterFile.SP));
        assertEquals(0x7000, ctx.registers().get(RegisterFile.FP));
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
        assertEquals(0x7000, ctx.inputBufferVAddr());
        assertEquals(0x8000, ctx.outputBufferVAddr());
        assertEquals(4096, ctx.ioBufferSize());
    }

    @Test
    void pageTableAccess() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        ctx.initialize();
        assertSame(mmu.getPageTableDirect(), ctx.pageTable(),
            "the execution page table is derived from its MMU");
        PageTable rebind = new PageTable();
        ctx.setPageTable(rebind);
        assertSame(rebind, ctx.pageTable());
    }

    @Test
    void callHostInvokesService() {
        SoftMMU mmu = createMappedMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = new ExecutionContext()
            .executionId("test")
            .fuelBudget(10000)
            .softMMU(mmu)
            .serviceRegistry(hsr)
            .allowedCapabilities(new String[]{"property.read"});
        ctx.initialize();
        ctx.hostContext(new me.majhrs16.suite.iflow.vm.service.HostContext() {
            @Override public String readProperty(int scope, int propId) { return "test-value"; }
        });
        ServiceResult result = ctx.callHost(HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 10, 0);
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
    void defaultStackLimitMatchesTheStackRegion() {
        ExecutionContext ctx = createContext(createMappedMMU());
        assertEquals(VmMemoryMap.STACK_TOP - VmMemoryMap.STACK_BASE, ctx.maxStackSize());
    }

    @Test
    void stackOverflowOnCallDetected() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu).maxStackSize(2);
        ctx.initialize();
        // Stack starts at 0x7000, max 2 bytes means 1 word
        ctx.registers().set(RegisterFile.SP, 0x7000);
        ctx.enterCall(0x7000); // First call: SP=0x7000, check passes
        // Simulate interpreter decrementing SP after first call
        ctx.registers().set(RegisterFile.SP, 0x6FFE);
        assertThrows(VmException.class, () -> ctx.enterCall(0x7000)); // Second call: SP=0x6FFE, would exceed 2 bytes
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

    // ===== DMA (D17) =====

    @Test
    void dmaWrapsTheExecutionSoftMMU() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        assertSame(mmu, ctx.dma().getBus().memory());
        assertSame(ctx.dma(), ctx.dma(), "the DMA must be cached per execution");
    }

    @Test
    void changingSoftMMUReDerivesTheDma() {
        ExecutionContext ctx = createContext(createMappedMMU());
        VmDmaController before = ctx.dma();
        SoftMMU other = createMappedMMU();
        ctx.softMMU(other);
        assertNotSame(before, ctx.dma());
        assertSame(other, ctx.dma().getBus().memory());
    }

    @Test
    void dmaWithoutSoftMMUThrows() {
        ExecutionContext ctx = new ExecutionContext();
        assertThrows(IllegalStateException.class, ctx::dma);
    }

    // ===== Bus (D18) =====

    @Test
    void busWrapsTheExecutionSoftMMU() {
        SoftMMU mmu = createMappedMMU();
        ExecutionContext ctx = createContext(mmu);
        assertSame(mmu, ctx.bus().memory());
        assertSame(ctx.bus(), ctx.bus(), "the bus must be cached per execution");
        // The CPU is a program master; the DMA is a supervisor master on the same
        // memory (design.md §14).
        assertSame(Privilege.PROGRAM, ctx.bus().privilege());
        assertSame(mmu, ctx.dma().getBus().memory(), "the DMA must target the execution memory");
        assertSame(Privilege.SUPERVISOR, ctx.dma().getBus().privilege());
    }

    @Test
    void changingSoftMMUReDerivesTheBus() {
        ExecutionContext ctx = createContext(createMappedMMU());
        VmBus before = ctx.bus();
        SoftMMU other = createMappedMMU();
        ctx.softMMU(other);
        assertNotSame(before, ctx.bus());
        assertSame(other, ctx.bus().memory());
        assertSame(other, ctx.dma().getBus().memory());
        assertSame(other.getPageTableDirect(), ctx.pageTable());
    }

    @Test
    void busWithoutSoftMMUThrows() {
        ExecutionContext ctx = new ExecutionContext();
        assertThrows(IllegalStateException.class, ctx::bus);
    }

    @Test
    void injectedBusProvidesTheDmaMemory() {
        // D18 seam: an explicitly wired bus must be the memory the DMA transacts on
        // (the DMA keeps its own supervisor privilege over that memory).
        SoftMMU contextMmu = createMappedMMU();
        SoftMMU busMmu = createMappedMMU();
        ExecutionContext ctx = createContext(contextMmu);
        VmBus bus = new VmBus(busMmu);
        ctx.bus(bus);
        assertSame(bus, ctx.bus());
        assertSame(busMmu, ctx.dma().getBus().memory());
        assertSame(Privilege.SUPERVISOR, ctx.dma().getBus().privilege());
        assertSame(busMmu.getPageTable(), ctx.pageTable());
    }

    @Test
    void initializeClearsBuffersThroughTheDma() {
        // Regression guard (D17): clearing the I/O buffers must go through the
        // injected DMA, not through the context's SoftMMU directly.
        SoftMMU contextMmu = createMappedMMU();
        SoftMMU dmaMmu = createMappedMMU();
        ExecutionContext ctx = createContext(contextMmu);
        // Dirty the DMA-side buffer, then wire a DMA over the second MMU.
        dmaMmu.writeBlock(ctx.inputBufferVAddr(), new byte[]{1, 2, 3}, 0, 3);
        ctx.dmaController(new VmDmaController(dmaMmu));
        // Dirty the context-side buffer too: it must be left untouched.
        contextMmu.writeByte(ctx.inputBufferVAddr(), 0x55);

        ctx.initialize();

        assertEquals(0, dmaMmu.readByte(ctx.inputBufferVAddr()),
            "the DMA-targeted buffer must be cleared");
        assertEquals(0x55, contextMmu.readByte(ctx.inputBufferVAddr()),
            "clearing must not bypass the DMA by touching the context MMU");
    }
}