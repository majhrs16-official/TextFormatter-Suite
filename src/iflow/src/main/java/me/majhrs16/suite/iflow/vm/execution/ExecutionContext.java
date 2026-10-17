package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageTable;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import me.majhrs16.suite.iflow.vm.service.ServiceResult;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Execution context for a single VM evaluation.
 * <p>
 * Contains all mutable state for one execution: registers, stack, fuel budget,
 * flat memory with SoftMMU (paged 4KB), capability set, property cache, and modification journal.
 * Contexts are pooled and reused; must be reset between executions.
 * </p>
 */
public final class ExecutionContext {

    // ===== Immutable configuration (set at acquisition) =====
    private String executionId;
    private String programId;
    private String messageId;
    private String actorId;        // The actor (sender/recipient) this execution is for
    private long fuelBudget;
    private int maxCallDepth = 64;
    private int maxStackSize = 16 * 1024; // 16 KiB
    private String[] allowedCapabilities = new String[0]; // Per-execution capabilities

    // ===== Runtime-injected services =====
    private SoftMMU softMMU;
    private HostServiceRegistry serviceRegistry;
    private Supplier<Long> nanoTimeSupplier = System::nanoTime;

    // ===== Mutable execution state =====
    private final RegisterFile registers = new RegisterFile();
    private final Map<String, Object> propertyCache = new HashMap<>();
    private final ModificationJournal journal = new ModificationJournal();
    private long fuelRemaining;
    private int callDepth = 0;
    // Shadow stack of frame pointers: CALL saves the caller's FP, RET restores it
    // (spec §7.4/§8.7: "RET restaura la dirección y el frame"). It lives outside the
    // VM memory stack on purpose, so the 2-byte return-address ABI stays unchanged.
    private int[] framePointerStack;
    private int framePointerDepth = 0;
    private boolean cancelled = false;
    private VmException fatalError;
    private long startNanos;

    // Page table for this execution (cloned from program's page table at init)
    private PageTable pageTable;

    // Pre-allocated I/O buffer virtual addresses, taken from the single-source
    // memory map (input at 0x7000, output at 0x8000).
    private static final int INPUT_BUFFER_VADDR = VmMemoryMap.INPUT_BASE;
    private static final int OUTPUT_BUFFER_VADDR = VmMemoryMap.OUTPUT_BASE;
    private static final int IO_BUFFER_SIZE = VmMemoryMap.IO_BUFFER_SIZE; // 4KB (one page)

    public ExecutionContext() {}

    // ===== Configuration =====

    public ExecutionContext executionId(String id) { this.executionId = id; return this; }
    public ExecutionContext programId(String id) { this.programId = id; return this; }
    public ExecutionContext messageId(String id) { this.messageId = id; return this; }
    public ExecutionContext actorId(String id) { this.actorId = id; return this; }

    /**
     * Sets the fuel budget and re-arms the remaining fuel to match.
     * <p>
     * Re-arming here — and not only in {@link #initialize()} — makes the setter
     * order-independent: a budget applied after {@code initialize()} is no
     * longer silently ignored (it used to leave {@code fuelRemaining} at the
     * previously armed value).
     * </p>
     */
    public ExecutionContext fuelBudget(long fuel) {
        this.fuelBudget = fuel;
        this.fuelRemaining = fuel;
        return this;
    }
    public ExecutionContext maxCallDepth(int d) { this.maxCallDepth = d; return this; }
    public ExecutionContext maxStackSize(int bytes) { this.maxStackSize = bytes; return this; }
    public ExecutionContext softMMU(SoftMMU mmu) { this.softMMU = mmu; return this; }
    public ExecutionContext serviceRegistry(HostServiceRegistry r) { this.serviceRegistry = r; return this; }
    public ExecutionContext nanoTimeSupplier(Supplier<Long> s) { this.nanoTimeSupplier = s; return this; }
    public ExecutionContext allowedCapabilities(String[] caps) { this.allowedCapabilities = caps != null ? caps.clone() : new String[0]; return this; }

    // ===== Initialization (called when acquiring from pool) =====

    public void initialize() {
        // Reset all mutable state
        registers.clearFlags();
        for (int i = 0; i < RegisterFile.REG_COUNT; i++) {
            registers.set(i, 0);
        }
        propertyCache.clear();
        journal.clear();
        fuelRemaining = fuelBudget;
        callDepth = 0;
        framePointerDepth = 0;
        if (framePointerStack == null || framePointerStack.length <= maxCallDepth) {
            framePointerStack = new int[maxCallDepth + 1];
        }
        cancelled = false;
        fatalError = null;
        startNanos = nanoTimeSupplier == null ? System.nanoTime() : nanoTimeSupplier.get();

        // Initialize stack pointer (stack grows down from the top of the stack region)
        registers.set(RegisterFile.SP, VmMemoryMap.STACK_TOP);
        registers.set(RegisterFile.FP, VmMemoryMap.STACK_TOP);

        // Clear I/O buffer pages in physical memory
        if (softMMU != null) {
            // Input buffer (0x7000) and output buffer (0x8000)
            for (int i = 0; i < IO_BUFFER_SIZE; i++) {
                softMMU.writeByte(INPUT_BUFFER_VADDR + i, 0);
                softMMU.writeByte(OUTPUT_BUFFER_VADDR + i, 0);
            }
        }
    }

    /** Resets the context for reuse (alias for initialize). */
    public void reset() {
        initialize();
    }

    // ===== Fuel management =====

    public long fuelRemaining() { return fuelRemaining; }
    public long fuelBudget() { return fuelBudget; }
    public long fuelConsumed() { return fuelBudget - fuelRemaining; }

    /** Consumes fuel, throws OUT_OF_FUEL if insufficient. */
    public void consumeFuel(long amount) {
        if (fuelRemaining < amount) {
            fuelRemaining = 0;
            throw VmException.outOfFuel(registers.get(RegisterFile.IP));
        }
        fuelRemaining -= amount;
    }

    /** Consumes fuel for a host service call (base + variable). */
    public void consumeHostFuel(long baseCost, long variableCost) {
        consumeFuel(baseCost + variableCost);
    }

    // ===== Call stack management =====

    /**
     * Enters a call frame: validates the call depth and stack limits, and saves the
     * caller's frame pointer so that {@link #exitCall()} can restore it.
     *
     * @param callerFramePointer the FP of the calling frame (saved for restoration)
     * @throws VmException STACK_LIMIT if the call depth or stack would be exceeded
     */
    public void enterCall(int callerFramePointer) {
        if (callDepth + 1 > maxCallDepth) {
            throw new VmException(VmException.Code.STACK_LIMIT,
                "Call depth exceeded: " + (callDepth + 1), registers.get(RegisterFile.IP));
        }
        // Check stack pointer against stack limit
        int sp = registers.get(RegisterFile.SP);
        int stackLimit = VmMemoryMap.STACK_TOP - maxStackSize; // Stack grows down from STACK_TOP
        if (sp - 2 < stackLimit) { // Need 2 bytes for return address
            throw new VmException(VmException.Code.STACK_LIMIT,
                "Stack overflow: SP=0x" + Integer.toHexString(sp) + " limit=0x" + Integer.toHexString(stackLimit),
                registers.get(RegisterFile.IP));
        }
        callDepth++;
        framePointerStack[framePointerDepth++] = callerFramePointer;
    }

    /**
     * Leaves the current call frame and restores the caller's frame pointer.
     *
     * @return the frame pointer of the calling frame
     * @throws VmException STACK_LIMIT if there is no active call frame (RET without CALL)
     */
    public int exitCall() {
        if (framePointerDepth == 0) {
            throw new VmException(VmException.Code.STACK_LIMIT,
                "Stack underflow on RET", registers.get(RegisterFile.IP));
        }
        if (callDepth > 0) callDepth--;
        return framePointerStack[--framePointerDepth];
    }

    public int callDepth() { return callDepth; }

    // ===== Property cache =====

    public Object getCachedProperty(String key) {
        return propertyCache.get(key);
    }

    public void cacheProperty(String key, Object value) {
        propertyCache.put(key, value);
    }

    public void invalidateProperty(String key) {
        propertyCache.remove(key);
    }

    public void clearPropertyCache() {
        propertyCache.clear();
    }

    // ===== Journal =====

    public ModificationJournal journal() { return journal; }

    // ===== Cancellation =====

    public boolean isCancelled() { return cancelled; }
    public void cancel() { cancelled = true; }
    public void checkCancelled() {
        if (cancelled) {
            throw VmException.cancelled(registers.get(RegisterFile.IP));
        }
    }

    public void setFatalError(VmException e) { this.fatalError = e; }
    public VmException fatalError() { return fatalError; }
    public boolean hasFatalError() { return fatalError != null; }

    // ===== Memory access (via SoftMMU) =====

    public SoftMMU softMMU() { return softMMU; }

    public PageTable pageTable() { return pageTable; }

    public void setPageTable(PageTable pageTable) {
        this.pageTable = pageTable;
    }

    /** Gets the virtual address of the input buffer (see {@link VmMemoryMap#INPUT_BASE}). */
    public int inputBufferVAddr() { return INPUT_BUFFER_VADDR; }

    /** Gets the virtual address of the output buffer (see {@link VmMemoryMap#OUTPUT_BASE}). */
    public int outputBufferVAddr() { return OUTPUT_BUFFER_VADDR; }

    /** Gets the size of I/O buffers (4096 bytes = 4KB = 1 page). */
    public int ioBufferSize() { return IO_BUFFER_SIZE; }

    // ===== Registers =====

    public RegisterFile registers() { return registers; }

    // ===== Host services =====

    public ServiceResult callHost(int serviceId, int argVAddr, int argLength, int options) {
        if (serviceRegistry == null) {
            throw new IllegalStateException("Service registry not configured");
        }
        return serviceRegistry.invoke(this, serviceId, argVAddr, argLength, options);
    }

    // ===== Timing =====

    public long elapsedNanos() {
        return (nanoTimeSupplier != null ? nanoTimeSupplier.get() : System.nanoTime()) - startNanos;
    }

    // ===== Getters =====

    public String executionId() { return executionId; }
    public String programId() { return programId; }
    public String messageId() { return messageId; }
    public String actorId() { return actorId; }
    public int maxCallDepth() { return maxCallDepth; }
    public int maxStackSize() { return maxStackSize; }
    public String[] allowedCapabilities() { return allowedCapabilities.clone(); }
    public boolean hasCapability(String capability) {
        for (String cap : allowedCapabilities) {
            if (cap.equals(capability)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "ExecutionContext{id=" + executionId + ", prog=" + programId
            + ", fuel=" + fuelRemaining + "/" + fuelBudget
            + ", depth=" + callDepth + ", cancelled=" + cancelled + "}";
    }
}