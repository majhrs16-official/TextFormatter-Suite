package me.majhrs16.suite.iflow.vm.video;

import me.majhrs16.suite.iflow.vm.memory.VmDevice;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;

/**
 * Memory-mapped text screen (the VM's video peripheral, D31).
 * <p>
 * It is a {@link VmDevice} (bus slave): once the BIOS/config maps a window into
 * the program's address space, <em>every</em> write the CPU or the DMA makes to
 * that window lands on the screen. The cell format follows the VERA/X16 style:
 * each cell is <b>two bytes</b>, the low byte a character and the high byte an
 * attribute nibble pair {@code [letter:4][background:4]} (high nibble = ink,
 * low nibble = paper).
 * </p>
 * <pre>
 *   +0  character (8 bits)
 *   +1  attribute:  bits 7..4 = ink colour (0-15)
 *                   bits 3..0 = paper colour (0-15)
 * </pre>
 * <p>
 * The screen is configured through a 16-byte MMIO register bank at
 * {@link VmMemoryMap#DEVICE_MMIO_BASE} (the BIOS writes it; it is reconfigurable
 * at runtime). Until it is enabled the device decodes nothing, so it costs
 * nothing and cannot shadow RAM.
 * </p>
 * <p>
 * The host reads the screen through the plain getters ({@link #renderText()},
 * {@link #renderAnsi()}, {@link #cellChar(int)}, …) so policies can emit useful
 * data, not only debug output; {@code VmPolicyEngine} additionally dumps it to
 * the log in debug builds.
 * </p>
 */
public final class VmDisplay implements VmDevice {

    // ===== MMIO register bank (offsets, 16-bit little-endian) =====
    public static final int REG_BASE    = 0x00; // framebuffer base address (0 = disabled)
    public static final int REG_COLS    = 0x02; // columns
    public static final int REG_ROWS    = 0x04; // rows
    public static final int REG_CTRL    = 0x06; // bit0 = enable, bit1 = clear (write-1)
    public static final int REG_CURSOR  = 0x08; // cursor cell index
    public static final int REG_STATUS  = 0x0A; // read-only

    /** CTRL bit 0: screen enabled. */
    public static final int CTRL_ENABLE = 0x0001;
    /** CTRL bit 1: clear the screen (write-only, self-clearing). */
    public static final int CTRL_CLEAR  = 0x0002;

    /** STATUS bit 0: enabled and configured. */
    public static final int STATUS_ENABLED = 0x0001;

    private static final int REG_COUNT = 16;
    private static final int MAX_DIM = 255;

    private final int ctrlBase;
    private final byte[] regs = new byte[REG_COUNT];

    // ===== Decoded configuration =====
    private int fbBase;
    private int cols;
    private int rows;
    private boolean enabled;
    private int cursor;

    /** Cell storage: two bytes per cell (character, then attribute). */
    private byte[] cells = new byte[0];

    /**
     * Creates a screen whose register bank sits at {@code ctrlBase}, initially
     * disabled (the BIOS/config enables it by writing the registers).
     */
    public VmDisplay(int ctrlBase) {
        this.ctrlBase = ctrlBase;
        writeRegister(REG_COLS, VmMemoryMap.DISPLAY_DEFAULT_COLS);
        writeRegister(REG_ROWS, VmMemoryMap.DISPLAY_DEFAULT_ROWS);
        reconfigure();
    }

    /** Creates a screen on the default MMIO aperture. */
    public VmDisplay() {
        this(VmMemoryMap.DEVICE_MMIO_BASE);
    }

    // ===== Bus slave =====

    @Override
    public boolean decodes(int vaddr) {
        if (vaddr >= ctrlBase && vaddr < ctrlBase + REG_COUNT) {
            return true;
        }
        return enabled && vaddr >= fbBase && vaddr < fbBase + cells.length;
    }

    @Override
    public int readByte(int vaddr) {
        if (vaddr >= ctrlBase && vaddr < ctrlBase + REG_COUNT) {
            int off = vaddr - ctrlBase;
            if (off == REG_STATUS) {
                return enabled ? STATUS_ENABLED : 0;
            }
            return regs[off] & 0xFF;
        }
        if (enabled && vaddr >= fbBase && vaddr < fbBase + cells.length) {
            return cells[vaddr - fbBase] & 0xFF;
        }
        return 0;
    }

    @Override
    public void writeByte(int vaddr, int value) {
        if (vaddr >= ctrlBase && vaddr < ctrlBase + REG_COUNT) {
            int off = vaddr - ctrlBase;
            if (off == REG_STATUS) {
                return; // read-only
            }
            regs[off] = (byte) value;
            reconfigure();
            return;
        }
        if (enabled && vaddr >= fbBase && vaddr < fbBase + cells.length) {
            cells[vaddr - fbBase] = (byte) value;
        }
    }

    // ===== Configuration =====

    private void writeRegister(int offset, int value) {
        regs[offset] = (byte) (value & 0xFF);
        regs[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private int readRegister(int offset) {
        return (regs[offset] & 0xFF) | ((regs[offset + 1] & 0xFF) << 8);
    }

    /**
     * Re-decodes the register bank after a write: validates the geometry, keeps
     * the framebuffer below the MMIO aperture (so it can never shadow the
     * device's own registers), and (re)allocates the cell store when needed.
     */
    private void reconfigure() {
        int newBase = readRegister(REG_BASE);
        int newCols = readRegister(REG_COLS);
        int newRows = readRegister(REG_ROWS);
        int ctrl = readRegister(REG_CTRL);
        int newCursor = readRegister(REG_CURSOR);

        boolean valid = newCols > 0 && newCols <= MAX_DIM
                     && newRows > 0 && newRows <= MAX_DIM
                     && newBase != 0;
        long size = valid ? (long) newCols * newRows * 2 : 0;
        // The window must fit below the register bank (MMIO aperture).
        if (valid && (newBase + size > ctrlBase)) {
            valid = false;
        }

        if (!valid) {
            enabled = false;
            return;
        }

        if (newCols != cols || newRows != rows || cells.length != size) {
            cols = newCols;
            rows = newRows;
            cells = blankCells((int) size);
        }
        fbBase = newBase;
        cursor = newCursor;
        enabled = (ctrl & CTRL_ENABLE) != 0;
        if ((ctrl & CTRL_CLEAR) != 0) {
            clearScreen();
            // CTRL_CLEAR is write-1/self-clearing: drop the bit once honoured.
            writeRegister(REG_CTRL, ctrl & ~CTRL_CLEAR);
        }
    }

    /** Erases every cell: characters become blanks, attributes zero. */
    public void clearScreen() {
        for (int i = 0; i < cells.length; i += 2) {
            cells[i] = (byte) ' ';
            cells[i + 1] = 0;
        }
    }

    /** A blank cell store: characters are spaces, attributes zero. */
    private static byte[] blankCells(int size) {
        byte[] store = new byte[size];
        for (int i = 0; i + 1 < store.length; i += 2) {
            store[i] = (byte) ' ';
        }
        return store;
    }

    // ===== Host API (production output + inspection) =====

    public boolean isEnabled() { return enabled; }
    public int baseAddress() { return fbBase; }
    public int columns() { return cols; }
    public int rows() { return rows; }
    public int cursor() { return cursor; }

    /** Character of a cell (0-based index). */
    public char cellChar(int index) {
        if (index < 0 || index * 2 >= cells.length) return 0;
        return (char) (cells[index * 2] & 0xFF);
    }

    /** Attribute of a cell: bits 7..4 ink, bits 3..0 paper. */
    public int cellAttribute(int index) {
        if (index < 0 || index * 2 + 1 >= cells.length) return 0;
        return cells[index * 2 + 1] & 0xFF;
    }

    public char cellChar(int col, int row) { return cellChar(row * cols + col); }
    public int cellAttribute(int col, int row) { return cellAttribute(row * cols + col); }

    /**
     * The screen as plain text: one line per row, characters only (attributes
     * dropped). Trailing spaces are preserved so the grid stays rectangular.
     */
    public String renderText() {
        if (cols == 0 || rows == 0) return "";
        StringBuilder sb = new StringBuilder((cols + 1) * rows);
        for (int r = 0; r < rows; r++) {
            if (r > 0) sb.append('\n');
            for (int c = 0; c < cols; c++) {
                sb.append(cellChar(r * cols + c));
            }
        }
        return sb.toString();
    }

    /** The screen as plain text with trailing blanks removed from each row. */
    public String renderTextTrimmed() {
        if (cols == 0 || rows == 0) return "";
        StringBuilder sb = new StringBuilder((cols + 1) * rows);
        for (int r = 0; r < rows; r++) {
            if (r > 0) sb.append('\n');
            int end = cols;
            while (end > 0 && cellChar(r * cols + end - 1) == ' ') {
                end--;
            }
            for (int c = 0; c < end; c++) {
                sb.append(cellChar(r * cols + c));
            }
        }
        return sb.toString();
    }

    /**
     * The screen as ANSI text: each cell is wrapped in a 16-colour SGR sequence
     * derived from its attribute (ink = high nibble, paper = low nibble). Useful
     * for a console dump; the raw framebuffer remains the canonical form.
     */
    public String renderAnsi() {
        if (cols == 0 || rows == 0) return "";
        StringBuilder sb = new StringBuilder((cols + 1) * rows * 6);
        for (int r = 0; r < rows; r++) {
            if (r > 0) sb.append('\n');
            for (int c = 0; c < cols; c++) {
                int attr = cellAttribute(r * cols + c);
                int ink = (attr >> 4) & 0x0F;
                int paper = attr & 0x0F;
                int inkCode = ink < 8 ? 30 + ink : 90 + (ink - 8);
                int paperCode = paper < 8 ? 40 + paper : 100 + (paper - 8);
                sb.append("\u001B[").append(inkCode).append(';').append(paperCode).append('m');
                sb.append(cellChar(r * cols + c));
            }
        }
        sb.append("\u001B[0m");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "VmDisplay[" + cols + "x" + rows + " @" + String.format("0x%04X", fbBase)
            + (enabled ? ", enabled" : ", disabled") + "]";
    }
}
