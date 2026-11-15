package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.Objects;

import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.DataMemory;
import me.majhrs16.suite.iflow.vm.isa.v3.InstructionReader;
import me.majhrs16.suite.iflow.vm.isa.v3.InterruptController;
import me.majhrs16.suite.iflow.vm.isa.v3.MachineFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemoryFault;
import me.majhrs16.suite.iflow.vm.isa.v3.MemorySize;
import me.majhrs16.suite.iflow.vm.isa.v3.TimeSource;
import me.majhrs16.suite.iflow.vm.rom.RomImage;
import me.majhrs16.suite.iflow.vm.rom.RomHeader;

/**
 * Complete v3 memory subsystem: MMU, physical bus, ROM/RAM and MMIO devices.
 *
 * <p>This is the only object the v3 CPU needs as {@link DataMemory} and
 * {@link InstructionReader}. It decodes MMIO before the MMU, as ADR-037
 * requires, and exposes the PIC through {@link InterruptController} so the CPU
 * can deliver an IRQ before the next fetch.</p>
 */
public final class MachineMemory implements DataMemory, InstructionReader, InterruptController {

    public static final int ABI_VERSION = 3;
    public static final int DEFAULT_FEATURES = SysInfo.FEATURE_TIMER
            | SysInfo.FEATURE_NVRAM
            | SysInfo.FEATURE_UART
            | SysInfo.FEATURE_DISPLAY
            | SysInfo.FEATURE_MAILBOX;

    private final CsrFile csrs;
    private final PhysicalMemory physical;
    private final Mmu mmu;
    private final TimeSource timeSource;
    private final Pic pic = new Pic();
    private final Timer timer;
    private final Uart uart;
    private final Display display = new Display();
    private final Nvram nvram;
    private final SysInfo sysInfo;
    private final HostMailbox mailbox;
    private final MmioDevice[] devices;

    public MachineMemory(CsrFile csrs, byte[] rom, int ramSize, TimeSource timeSource) {
        this(csrs, rom, ramSize, timeSource, new Nvram(new byte[Nvram.BYTES]),
                80, 0, ABI_VERSION, DEFAULT_FEATURES);
    }

    public MachineMemory(CsrFile csrs, byte[] rom, int ramSize, TimeSource timeSource,
                         Nvram nvram, int tocOffset, int tocCount, int abiVersion,
                         int features) {
        this.csrs = Objects.requireNonNull(csrs, "csrs");
        this.physical = new PhysicalMemory(rom, ramSize);
        this.mmu = new Mmu(physical);
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.nvram = Objects.requireNonNull(nvram, "nvram");
        this.timer = new Timer(timeSource, pic);
        this.uart = new Uart(pic);
        this.sysInfo = new SysInfo(ramSize, physical.romSize(), tocOffset, tocCount,
                abiVersion, features);
        this.mailbox = new HostMailbox(pic);
        this.devices = new MmioDevice[] {uart, timer, pic, display, nvram, sysInfo, mailbox};
    }

    /**
     * Cold-resets the machine memory: RAM, translations and volatile devices.
     * NVRAM is persistent and keeps its committed bytes across a reset.
     */
    public void reset() {
        physical.clearRam();
        mmu.flush();
        pic.reset();
        timer.reset();
        uart.reset();
        display.reset();
        mailbox.reset();
        nvram.reset(nvram.persistentBytes());
    }

    public static MachineMemory fromRom(CsrFile csrs, RomImage rom, int ramSize,
                                        TimeSource timeSource, boolean syncAvailable) {
        Objects.requireNonNull(rom, "rom");
        RomHeader header = rom.header();
        int features = DEFAULT_FEATURES
                | (syncAvailable ? SysInfo.FEATURE_SYNC : 0);
        return new MachineMemory(csrs, rom.bytes(), ramSize, timeSource,
                new Nvram(new byte[Nvram.BYTES]),
                header.tocOffset(), header.tocCount(), header.abiVersion(), features);
    }

    // ===== Bus contract =====

    @Override
    public int read(int address, MemorySize size) {
        Objects.requireNonNull(size, "size");
        if (physical.isMmio(address)) {
            return readMmio(address, size);
        }
        if (!csrs.status().mmuEnabled() && !csrs.status().supervisor()) {
            throw MemoryFault.privilege(address, "MMU is disabled and user mode is not allowed");
        }
        int[] physicalAddresses = translateBytes(address, size, Mmu.Access.READ);
        int value = 0;
        for (int index = 0; index < physicalAddresses.length; index++) {
            value |= physical.readByte(physicalAddresses[index]) << (8 * index);
        }
        return value;
    }

    @Override
    public void write(int address, MemorySize size, int value) {
        Objects.requireNonNull(size, "size");
        if (physical.isMmio(address)) {
            writeMmio(address, size, value);
            return;
        }
        if (!csrs.status().mmuEnabled() && !csrs.status().supervisor()) {
            throw MemoryFault.privilege(address, "MMU is disabled and user mode is not allowed");
        }
        int[] physicalAddresses = translateBytes(address, size, Mmu.Access.WRITE);
        for (int index = 0; index < physicalAddresses.length; index++) {
            int physicalAddress = physicalAddresses[index];
            physical.writeByte(physicalAddress, value >>> (8 * index));
            mmu.notePhysicalWrite(physicalAddress);
        }
    }

    @Override
    public int readByte(int address) {
        if (physical.isMmio(address)) {
            throw MachineFault.instructionFault(address,
                    "MMIO is not executable");
        }
        try {
            int physicalAddress = csrs.status().mmuEnabled()
                    ? mmu.translate(address, Mmu.Access.EXECUTE, csrs.status(), csrs.ptbr())
                    : address;
            if (!csrs.status().mmuEnabled() && !csrs.status().supervisor()) {
                throw MemoryFault.privilege(address, "MMU is disabled and user mode is not allowed");
            }
            return physical.readByte(physicalAddress);
        } catch (MemoryFault fault) {
            throw new MachineFault(fault.code(), address, fault.badAddress(), fault.detail());
        }
    }

    @Override
    public void fence() {
        timer.tick();
    }

    @Override
    public void flushTlb() {
        mmu.flush();
    }

    @Override
    public void poll() {
        timer.tick();
    }

    @Override
    public int takePending() {
        timer.tick();
        return pic.takePending();
    }

    // ===== MMIO =====

    private int readMmio(int address, MemorySize size) {
        checkMmioAccess(address, size);
        MmioDevice device = deviceFor(address);
        if (device == null) {
            throw MemoryFault.busError(address, "reserved MMIO address");
        }
        timer.tick();
        try {
            return device.readRegister(address - device.base());
        } catch (IllegalArgumentException error) {
            throw MemoryFault.busError(address, error.getMessage());
        }
    }

    private void writeMmio(int address, MemorySize size, int value) {
        checkMmioAccess(address, size);
        MmioDevice device = deviceFor(address);
        if (device == null) {
            throw MemoryFault.busError(address, "reserved MMIO address");
        }
        timer.tick();
        try {
            device.writeRegister(address - device.base(), value);
        } catch (IllegalArgumentException error) {
            throw MemoryFault.busError(address, error.getMessage());
        }
    }

    private void checkMmioAccess(int address, MemorySize size) {
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

    private int[] translateBytes(int address, MemorySize size, Mmu.Access access) {
        checkAlignment(address, size);
        int[] physicalAddresses = new int[size.bytes()];
        for (int index = 0; index < physicalAddresses.length; index++) {
            long current = Integer.toUnsignedLong(address) + index;
            if (current > 0xFFFF_FFFFL) {
                throw MemoryFault.busError(address, "access crosses the 32-bit space");
            }
            physicalAddresses[index] = csrs.status().mmuEnabled()
                    ? mmu.translate((int) current, access, csrs.status(), csrs.ptbr())
                    : (int) current;
        }
        return physicalAddresses;
    }

    private static void checkAlignment(int address, MemorySize size) {
        int mask = size.bytes() - 1;
        if ((address & mask) != 0) {
            throw MemoryFault.alignment(address,
                    "unaligned " + size.bytes() + "-byte access");
        }
    }

    // ===== Accessors =====

    public CsrFile csrs() {
        return csrs;
    }

    public PhysicalMemory physical() {
        return physical;
    }

    public Mmu mmu() {
        return mmu;
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
