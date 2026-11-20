package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.iflow.vm.isa.v3.AtomicMemory;
import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.DataMemory;
import me.majhrs16.suite.iflow.vm.isa.v3.InstructionReader;
import me.majhrs16.suite.iflow.vm.isa.v3.InterruptController;
import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemorySize;
import me.majhrs16.suite.iflow.vm.isa.v3.TimeSource;
import me.majhrs16.suite.iflow.vm.rom.RomImage;
import me.majhrs16.suite.iflow.vm.rom.RomHeader;

/**
 * Shared v3 machine memory: physical bus, ROM/RAM, MMIO devices and per-core
 * architectural views.
 *
 * <p>The machine owns the devices, the physical memory and the atomic
 * reservation map. Each {@link CoreMemory} owns its CSR file and TLB, so a
 * vCPU can execute with its own page table without duplicating RAM or MMIO
 * state. The direct methods of this class are the view of the default core
 * passed to the constructor, which keeps the single-core API stable.</p>
 */
public final class MachineMemory
        implements DataMemory, InstructionReader, InterruptController, AtomicMemory {

    public static final int ABI_VERSION = 3;
    public static final int DEFAULT_FEATURES = SysInfo.FEATURE_TIMER
            | SysInfo.FEATURE_NVRAM
            | SysInfo.FEATURE_UART
            | SysInfo.FEATURE_DISPLAY
            | SysInfo.FEATURE_MAILBOX;

    private final int coreCount;
    private final PhysicalMemory physical;
    private final TimeSource timeSource;
    private final Pic pic;
    private final Timer timer;
    private final Uart uart;
    private final Display display = new Display();
    private final Nvram nvram;
    private final SysInfo sysInfo;
    private final HostMailbox mailbox;
    private final MmioDevice[] devices;
    private final Object atomicLock = new Object();
    private final Map<Integer, Integer> reservations = new HashMap<>();
    private final Map<Integer, CoreMemory> cores = new HashMap<>();
    private final CoreMemory defaultCore;

    public MachineMemory(CsrFile csrs, byte[] rom, int ramSize, TimeSource timeSource) {
        this(csrs, rom, ramSize, timeSource, new Nvram(new byte[Nvram.BYTES]),
                80, 0, ABI_VERSION, DEFAULT_FEATURES);
    }

    public MachineMemory(CsrFile csrs, byte[] rom, int ramSize, TimeSource timeSource,
                         Nvram nvram, int tocOffset, int tocCount, int abiVersion,
                         int features) {
        this(csrs, rom, ramSize, timeSource, nvram, tocOffset, tocCount, abiVersion,
                features, 1);
    }

    public MachineMemory(CsrFile csrs, byte[] rom, int ramSize, TimeSource timeSource,
                         Nvram nvram, int tocOffset, int tocCount, int abiVersion,
                         int features, int coreCount) {
        Objects.requireNonNull(csrs, "csrs");
        if (coreCount < Pic.MIN_CORE_COUNT || coreCount > Pic.MAX_CORE_COUNT) {
            throw new IllegalArgumentException(
                    "CORE_COUNT must be " + Pic.MIN_CORE_COUNT + ".." + Pic.MAX_CORE_COUNT
                            + ": " + coreCount);
        }
        if (csrs.coreId() >= coreCount) {
            throw new IllegalArgumentException(
                    "COREID " + csrs.coreId() + " exceeds CORE_COUNT " + coreCount);
        }
        this.coreCount = coreCount;
        this.physical = new PhysicalMemory(rom, ramSize);
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.nvram = Objects.requireNonNull(nvram, "nvram");
        this.pic = new Pic(coreCount);
        this.timer = new Timer(timeSource, pic);
        this.uart = new Uart(pic);
        this.sysInfo = new SysInfo(ramSize, physical.romSize(), tocOffset, tocCount,
                abiVersion, features, coreCount);
        this.mailbox = new HostMailbox(pic);
        this.devices = new MmioDevice[] {uart, timer, pic, display, nvram, sysInfo, mailbox};
        this.defaultCore = new CoreMemory(this, csrs);
        this.cores.put(csrs.coreId(), defaultCore);
    }

    /**
     * Cold-resets the machine memory: RAM, translations and volatile devices.
     * NVRAM is persistent and keeps its committed bytes across a reset.
     */
    public void reset() {
        physical.clearRam();
        synchronized (cores) {
            for (CoreMemory core : cores.values()) {
                core.reset();
            }
        }
        pic.reset();
        timer.reset();
        uart.reset();
        display.reset();
        mailbox.reset();
        nvram.reset(nvram.persistentBytes());
        synchronized (atomicLock) {
            reservations.clear();
        }
    }

    public static MachineMemory fromRom(CsrFile csrs, RomImage rom, int ramSize,
                                        TimeSource timeSource, boolean syncAvailable) {
        return fromRom(csrs, rom, ramSize, timeSource, syncAvailable, 1);
    }

    public static MachineMemory fromRom(CsrFile csrs, RomImage rom, int ramSize,
                                        TimeSource timeSource, boolean syncAvailable,
                                        int coreCount) {
        Objects.requireNonNull(rom, "rom");
        RomHeader header = rom.header();
        int features = DEFAULT_FEATURES
                | (syncAvailable ? SysInfo.FEATURE_SYNC : 0);
        return new MachineMemory(csrs, rom.bytes(), ramSize, timeSource,
                new Nvram(new byte[Nvram.BYTES]),
                header.tocOffset(), header.tocCount(), header.abiVersion(), features,
                coreCount);
    }

    /**
     * Returns the architectural view of one core, creating it on first use.
     *
     * @param coreId core index in {@code 0..CORE_COUNT-1}
     */
    public CoreMemory core(int coreId) {
        if (coreId < 0 || coreId >= coreCount) {
            throw new IllegalArgumentException(
                    "COREID out of range: " + coreId + " (core-count " + coreCount + ")");
        }
        synchronized (cores) {
            CoreMemory existing = cores.get(coreId);
            if (existing != null) {
                return existing;
            }
            CoreMemory created = new CoreMemory(this, new CsrFile(coreId));
            cores.put(coreId, created);
            return created;
        }
    }

    // ===== Bus contract =====

    @Override
    public int read(int address, MemorySize size) {
        return defaultCore.read(address, size);
    }

    @Override
    public void write(int address, MemorySize size, int value) {
        defaultCore.write(address, size, value);
    }

    @Override
    public int readByte(int address) {
        return defaultCore.readByte(address);
    }

    @Override
    public void fence() {
        defaultCore.fence();
    }

    @Override
    public void flushTlb() {
        defaultCore.flushTlb();
    }

    @Override
    public void poll() {
        defaultCore.poll();
    }

    @Override
    public int takePending() {
        return defaultCore.takePending();
    }

    // ===== Atomics =====

    @Override
    public int loadLinked(int address) {
        return defaultCore.loadLinked(address);
    }

    @Override
    public boolean storeConditional(int address, int value) {
        return defaultCore.storeConditional(address, value);
    }

    @Override
    public int atomic(int address, Operation operation, int operand) {
        return defaultCore.atomic(address, operation, operand);
    }

    @Override
    public void clearReservation() {
        defaultCore.clearReservation();
    }

    Object atomicLock() {
        return atomicLock;
    }

    Map<Integer, Integer> reservations() {
        return reservations;
    }

    void invalidateOtherReservations(int coreId, int physicalAddress) {
        synchronized (atomicLock) {
            reservations.entrySet().removeIf(
                    entry -> entry.getKey() != coreId && entry.getValue() == physicalAddress);
        }
    }

    // ===== MMIO =====

    int readMmio(CsrFile csrs, int address, MemorySize size) {
        checkMmioAccess(csrs, address, size);
        MmioDevice device = deviceFor(address);
        if (device == null) {
            throw MemoryFault.busError(address, "reserved MMIO address");
        }
        timer.tick();
        try {
            return device.readRegister(address - device.base(), csrs.coreId());
        } catch (IllegalArgumentException error) {
            throw MemoryFault.busError(address, error.getMessage());
        }
    }

    void writeMmio(CsrFile csrs, int address, MemorySize size, int value) {
        checkMmioAccess(csrs, address, size);
        MmioDevice device = deviceFor(address);
        if (device == null) {
            throw MemoryFault.busError(address, "reserved MMIO address");
        }
        timer.tick();
        try {
            device.writeRegister(address - device.base(), value, csrs.coreId());
        } catch (IllegalArgumentException error) {
            throw MemoryFault.busError(address, error.getMessage());
        }
    }

    private static void checkMmioAccess(CsrFile csrs, int address, MemorySize size) {
        if (!csrs.status().supervisor()) {
            throw MemoryFault.privilege(address, "MMIO requires supervisor mode");
        }
        if (size != MemorySize.WORD) {
            throw MemoryFault.alignment(address, "MMIO requires a 32-bit access");
        }
        if ((address & 3) != 0) {
            throw MemoryFault.alignment(address, "MMIO requires 4-byte alignment");
        }
        if (Integer.toUnsignedLong(address) + size.bytes()
                > Integer.toUnsignedLong(PhysicalMemory.MMIO_END)) {
            throw MemoryFault.busError(address, "MMIO access crosses the aperture");
        }
    }

    public MmioDevice deviceFor(int address) {
        for (MmioDevice device : devices) {
            if (device.decodes(address)) {
                return device;
            }
        }
        return null;
    }

    // ===== Accessors =====

    public CsrFile csrs() {
        return defaultCore.csrs();
    }

    public int coreCount() {
        return coreCount;
    }

    public PhysicalMemory physical() {
        return physical;
    }

    public Mmu mmu() {
        return defaultCore.mmu();
    }

    public Pic pic() {
        return pic;
    }

    public Timer timer() {
        return timer;
    }

    public Uart uart() {
        return uart;
    }

    public Display display() {
        return display;
    }

    public Nvram nvram() {
        return nvram;
    }

    public SysInfo sysInfo() {
        return sysInfo;
    }

    public HostMailbox mailbox() {
        return mailbox;
    }

    public TimeSource timeSource() {
        return timeSource;
    }
}
