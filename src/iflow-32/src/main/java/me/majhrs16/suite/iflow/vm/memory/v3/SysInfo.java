package me.majhrs16.suite.iflow.vm.memory.v3;

/**
 * Read-only v3 system information block at {@code 0x0100_5000} (ADR-037 §5.8).
 */
public final class SysInfo implements MmioDevice {

    public static final int BASE = 0x0100_5000;

    public static final int RAM_SIZE = 0x00;
    public static final int RAM_BASE = 0x04;
    public static final int ROM_SIZE = 0x08;
    public static final int TOC_OFFSET = 0x0C;
    public static final int TOC_COUNT = 0x10;
    public static final int CORE_COUNT = 0x14;
    public static final int ABI_VERSION = 0x18;
    public static final int FEATURES = 0x1C;
    public static final int CPU_HZ = 0x20;
    public static final int TIME_HZ = 0x24;

    public static final int FEATURE_TIMER = 1;
    public static final int FEATURE_NVRAM = 1 << 1;
    public static final int FEATURE_UART = 1 << 2;
    public static final int FEATURE_DISPLAY = 1 << 3;
    public static final int FEATURE_SYNC = 1 << 4;
    public static final int FEATURE_MAILBOX = 1 << 5;
    public static final int FEATURE_MULTICORE = 1 << 6;

    public static final int MIN_CORE_COUNT = 1;
    public static final int MAX_CORE_COUNT = 64;

    public static final int CLOCK_HZ = 1_000_000_000;

    private final int ramSize;
    private final int romSize;
    private final int tocOffset;
    private final int tocCount;
    private final int coreCount;
    private final int abiVersion;
    private final int features;

    public SysInfo(int ramSize, int romSize, int tocOffset, int tocCount,
                   int abiVersion, int features) {
        this(ramSize, romSize, tocOffset, tocCount, abiVersion, features, 1);
    }

    public SysInfo(int ramSize, int romSize, int tocOffset, int tocCount,
                   int abiVersion, int features, int coreCount) {
        if (coreCount < MIN_CORE_COUNT || coreCount > MAX_CORE_COUNT) {
            throw new IllegalArgumentException("CORE_COUNT must be 1..64: " + coreCount);
        }
        this.ramSize = ramSize;
        this.romSize = romSize;
        this.tocOffset = tocOffset;
        this.tocCount = tocCount;
        this.coreCount = coreCount;
        this.abiVersion = abiVersion;
        this.features = coreCount > 1
                ? features | FEATURE_MULTICORE
                : features & ~FEATURE_MULTICORE;
    }

    @Override
    public int base() {
        return BASE;
    }

    @Override
    public int readRegister(int offset) {
        return switch (offset) {
            case RAM_SIZE -> ramSize;
            case RAM_BASE -> PhysicalMemory.RAM_BASE;
            case ROM_SIZE -> romSize;
            case TOC_OFFSET -> tocOffset;
            case TOC_COUNT -> tocCount;
            case CORE_COUNT -> coreCount;
            case ABI_VERSION -> abiVersion;
            case FEATURES -> features;
            case CPU_HZ, TIME_HZ -> CLOCK_HZ;
            default -> throw new IllegalArgumentException(
                    "Unknown SYSINFO register 0x" + Integer.toHexString(offset));
        };
    }

    @Override
    public void writeRegister(int offset, int value) {
        throw new IllegalArgumentException(
                "SYSINFO is read-only: register 0x" + Integer.toHexString(offset));
    }
}
