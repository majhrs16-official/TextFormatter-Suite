package me.majhrs16.suite.iflow.vm.memory;

/**
 * Single source of truth for the iFlow VM's flat 64 KB memory map.
 * <p>
 * The map is deliberately <em>independent of whether the firmware ROM is
 * enabled</em>: the code region always reserves two pages. A firmware-on image
 * lays the ROM at {@code 0x0000} (page 0) and the user code at {@code 0x1000}
 * (page 1); a bare-metal image lays the user code at {@code 0x0000} and simply
 * leaves page 1 unused. Either way the code region never overlaps the constants
 * region, so code and read-only data can never alias.
 * </p>
 *
 * <pre>
 *   page 0      0x0000-0x0FFF   code: firmware ROM / bare-metal user code   R/X
 *   page 1      0x1000-0x1FFF   code: user code (when firmware is enabled)   R/X
 *   page 2      0x2000-0x2FFF   constants (read-only section of the image)   R
 *   pages 3-4   0x3000-0x4FFF   data / heap                                 R/W
 *   pages 5-6   0x5000-0x6FFF   stack (grows down from {@link #STACK_TOP})   R/W
 *   page 7      0x7000-0x7FFF   input buffer (I/O)                         R/W
 *   page 8      0x8000-0x8FFF   output buffer (I/O)                        R/W
 *   pages 9-15  0x9000-0xFFFF   reserved / persistent state
 * </pre>
 *
 * <p>
 * Consumers (parser, engine, execution context) must derive their addresses from
 * here instead of hardcoding page numbers, so the map cannot drift.
 * </p>
 */
public final class VmMemoryMap {

    private VmMemoryMap() {}

    /** Page size in bytes (4 KB). */
    public static final int PAGE = SoftMMU.PAGE_SIZE;
    /** Page shift: {@code vaddr >> PAGE_SHIFT == page index}. */
    public static final int PAGE_SHIFT = SoftMMU.PAGE_SHIFT;

    // ===== Code region: firmware ROM (page 0) + user code (page 1) =====

    public static final int CODE_BASE = 0x0000;
    public static final int CODE_PAGES = 2;
    /** Maximum size of the whole code section (ROM + user code). */
    public static final int CODE_MAX_SIZE = CODE_PAGES * PAGE; // 8192

    // ===== Constants: read-only data section of the .flow image =====

    public static final int CONSTANTS_BASE = 0x2000;
    public static final int CONSTANTS_PAGES = 1;
    /** Maximum size of the constants section, in bytes. */
    public static final int CONSTANTS_MAX_SIZE = CONSTANTS_PAGES * PAGE; // 4096

    // ===== Data / heap =====

    public static final int DATA_BASE = 0x3000;
    public static final int DATA_PAGES = 2;

    // ===== Stack: grows down from STACK_TOP =====

    public static final int STACK_BASE = 0x5000;
    public static final int STACK_PAGES = 2;
    /** Initial SP/FP: top of the stack region, one past the last usable byte. */
    public static final int STACK_TOP = STACK_BASE + STACK_PAGES * PAGE; // 0x7000

    // ===== I/O buffers =====

    public static final int INPUT_BASE = 0x7000;
    public static final int OUTPUT_BASE = 0x8000;
    public static final int IO_PAGES = 1;
    public static final int IO_BUFFER_SIZE = IO_PAGES * PAGE; // 4096

    /** Page index of a virtual address. */
    public static int pageOf(int vaddr) {
        return vaddr >>> PAGE_SHIFT;
    }
}
