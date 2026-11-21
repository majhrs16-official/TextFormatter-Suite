package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.Arrays;

/**
 * v3 text display at {@code 0x0100_3000} (ADR-037 §5.8).
 *
 * <p>The framebuffer is 80x25 two-byte cells (character and attribute) at
 * window offset {@code 0x10}. Because MMIO is word-only, each 32-bit access
 * updates two cells. The display has no IRQ in v3.0.</p>
 */
public final class Display implements MmioDevice {

    public static final int BASE = 0x0100_3000;
    public static final int COLS = 0x00;
    public static final int ROWS = 0x04;
    public static final int CURSOR = 0x08;
    public static final int CTRL = 0x0C;
    public static final int BUFFER = 0x10;

    public static final int COLUMNS = 80;
    public static final int ROWS_COUNT = 25;
    public static final int CELL_BYTES = 2;
    public static final int BUFFER_BYTES = COLUMNS * ROWS_COUNT * CELL_BYTES;

    public static final int CTRL_ENABLE = 1;
    public static final int CTRL_CLEAR = 1 << 1;

    private final byte[] cells = new byte[BUFFER_BYTES];
    private int cursor;
    private int control;

    @Override
    public int base() {
        return BASE;
    }

    @Override
    public synchronized int readRegister(int offset) {
        return switch (offset) {
            case COLS -> COLUMNS;
            case ROWS -> ROWS_COUNT;
            case CURSOR -> cursor;
            case CTRL -> control;
            default -> {
                int cellOffset = cellOffset(offset);
                yield (cells[cellOffset] & 0xFF)
                        | ((cells[cellOffset + 1] & 0xFF) << 8)
                        | ((cells[cellOffset + 2] & 0xFF) << 16)
                        | ((cells[cellOffset + 3] & 0xFF) << 24);
            }
        };
    }

    @Override
    public synchronized void writeRegister(int offset, int value) {
        switch (offset) {
            case COLS, ROWS -> {
                // Read-only geometry.
            }
            case CURSOR -> cursor = value;
            case CTRL -> {
                control = value & CTRL_ENABLE;
                if ((value & CTRL_CLEAR) != 0) {
                    clear();
                }
            }
            default -> {
                int cellOffset = cellOffset(offset);
                cells[cellOffset] = (byte) value;
                cells[cellOffset + 1] = (byte) (value >>> 8);
                cells[cellOffset + 2] = (byte) (value >>> 16);
                cells[cellOffset + 3] = (byte) (value >>> 24);
            }
        }
    }

    public synchronized void reset() {
        clear();
        cursor = 0;
        control = 0;
    }

    public synchronized void clear() {
        Arrays.fill(cells, (byte) 0);
    }

    public synchronized byte[] snapshot() {
        return cells.clone();
    }

    private static int cellOffset(int offset) {
        int relative = offset - BUFFER;
        if (relative < 0 || relative + Integer.BYTES > BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "display framebuffer register 0x" + Integer.toHexString(offset));
        }
        return relative;
    }
}
