package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.Arrays;
import java.util.Objects;

/**
 * 4 KiB v3 NVRAM at {@code 0x0100_4000} (ADR-037 §5.8).
 *
 * <p>The guest programs an address and accesses one byte through {@code DATA};
 * {@code COMMIT} publishes the working copy as the persistent snapshot. The host
 * reads {@link #persistentBytes()} when the VM powers off and passes it back on
 * the next boot.</p>
 */
public final class Nvram implements MmioDevice {

    public static final int BASE = 0x0100_4000;
    public static final int SIZE = 0x00;
    public static final int ADDR = 0x04;
    public static final int DATA = 0x08;
    public static final int COMMIT = 0x0C;

    public static final int BYTES = 4096;

    private final byte[] working;
    private final byte[] committed;
    private int address;

    public Nvram(byte[] initial) {
        Objects.requireNonNull(initial, "initial");
        if (initial.length != BYTES) {
            throw new IllegalArgumentException("NVRAM must be exactly " + BYTES + " bytes");
        }
        working = initial.clone();
        committed = initial.clone();
    }

    @Override
    public int base() {
        return BASE;
    }

    @Override
    public int readRegister(int offset) {
        return switch (offset) {
            case SIZE -> BYTES;
            case ADDR -> address;
            case DATA -> working[address & (BYTES - 1)] & 0xFF;
            case COMMIT -> 0;
            default -> throw new IllegalArgumentException(
                    "Unknown NVRAM register 0x" + Integer.toHexString(offset));
        };
    }

    @Override
    public void writeRegister(int offset, int value) {
        switch (offset) {
            case SIZE -> {
                // Read-only size.
            }
            case ADDR -> address = value & (BYTES - 1);
            case DATA -> working[address & (BYTES - 1)] = (byte) value;
            case COMMIT -> {
                if (value == 1) {
                    commit();
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unknown NVRAM register 0x" + Integer.toHexString(offset));
        }
    }

    public void commit() {
        System.arraycopy(working, 0, committed, 0, BYTES);
    }

    public byte[] persistentBytes() {
        return committed.clone();
    }

    public byte[] workingBytes() {
        return working.clone();
    }

    public void reset(byte[] persistent) {
        Objects.requireNonNull(persistent, "persistent");
        if (persistent.length != BYTES) {
            throw new IllegalArgumentException("NVRAM must be exactly " + BYTES + " bytes");
        }
        System.arraycopy(persistent, 0, working, 0, BYTES);
        System.arraycopy(persistent, 0, committed, 0, BYTES);
        address = 0;
    }

    @Override
    public String toString() {
        return "Nvram[committed=" + Arrays.hashCode(committed) + "]";
    }
}
