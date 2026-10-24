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
 *   pages 9-15  0x9000-0xFFFF   general-purpose user RAM                   R/W
 *   0x0F00      0x0F00-0x0F0F   screen MMIO registers (in the BIOS page)    I/O
 *   0x0F10      0x0F10-0x0FEF   BIOS NVRAM / scratch window                 I/O
 *   0x0FF0      0x0FF0-0x0FFF   interrupt controller (PIC) registers        I/O
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

    /**
     * Output buffer: <em>reserved scratch</em> (IFLOW-AUD-041). It is mapped R/W and
     * cleared on every execution, but no production path reads it — a policy's result
     * travels exclusively through the modification journal. A program may use it as
     * free scratch space; it stays mapped (and reachable from the host through
     * {@code ExecutionContext.outputBufferVAddr}) as a stable region for diagnostics
     * or a future custom sink.
     */
    public static final int OUTPUT_BASE = 0x8000;
    public static final int IO_PAGES = 1;
    public static final int IO_BUFFER_SIZE = IO_PAGES * PAGE; // 4096

    // ===== General-purpose user RAM =====

    /**
     * Remaining address space, mapped R/W and owned by the program. It is
     * deliberately <em>not</em> reserved for any device or runtime subsystem:
     * a program can use it as free storage (counters, tables, caches…). Because
     * the VM keeps a single long-lived machine (see {@code VmPolicyEngine}), what
     * the program leaves here is simply still there on the next message; the
     * program, not the runtime, decides when to clear or reuse it.
     * <p>
     * The last page (see {@link #DISPLAY_WINDOW_BASE}) is shared with the text
     * screen: while the screen is enabled its framebuffer shadows the first
     * {@code cols × rows × 2} bytes of that page, and the rest stays RAM. When
     * the program disables the screen, the whole page is ordinary RAM again.
     * </p>
     */
    public static final int USER_BASE = 0x9000;
    public static final int USER_PAGES = 7;
    public static final int USER_SIZE = USER_PAGES * PAGE; // 28 KB

    // ===== Device MMIO aperture (peripherals) =====

    /**
     * Base of the 256-byte MMIO aperture where peripherals expose their register
     * banks (the text screen of D31, the BIOS NVRAM of D32 and the PIC of D33). It
     * lives <em>inside the BIOS ROM page</em> (0x0000-0x0FFF): the firmware
     * functions end well before it (currently at 0x029B), so the unused tail of the
     * ROM page is reused for the device control block instead of carving a hole out
     * of user RAM.
     * <p>
     * <b>Access contract (IFLOW-AUD-031, decided 2026-10-19).</b> The aperture is
     * <em>device space</em>, not memory: the interconnect ({@link VmBus}) decodes it
     * <em>before</em> the MMU, so neither page ownership nor per-page permissions
     * govern it — a device transaction is never translated nor privilege-checked,
     * exactly like real memory-mapped I/O (see {@link VmDevice}). This is deliberate:
     * the program domain drives its own peripherals (the screen of D31, and the PIC
     * of D33 whose lines the program enables and raises; the IRQ table in NVRAM is
     * meant to be rewritten by it), so a privilege gate here would also break the
     * BIOS, which runs in the {@link Privilege#PROGRAM} domain. Page ownership keeps
     * protecting <em>memory</em>: a program still cannot write kernel-owned RAM
     * (ROM, constants). The only BIOS-private datum in the aperture is the NVRAM
     * cold-boot flag; a program overwriting it can only force a cold boot of its own
     * machine (clearing its volatile RAM), never reach beyond it. The behaviour is
     * pinned by {@code VmDisplayTest#programMayDriveDeviceRegistersInsideTheKernelPage}.
     * </p>
     */
    public static final int DEVICE_MMIO_BASE = 0x0F00;
    public static final int DEVICE_MMIO_SIZE = 0x0100; // 256 bytes

    /**
     * Base of the BIOS non-volatile scratch window (NVRAM), carved out of the
     * device MMIO aperture right after the screen's 16 register bytes. The BIOS
     * uses it for state that must survive across messages (today the cold-boot
     * flag that makes the reset routine clear volatile RAM exactly once). Like
     * every device register, it is decoded by the interconnect before the MMU,
     * so it is not part of the program's RAM.
     */
    public static final int DEVICE_NVRAM_BASE = DEVICE_MMIO_BASE + 0x10;   // 0x0F10
    public static final int DEVICE_NVRAM_SIZE = 0xE0;                      // 0x0F10-0x0FEF

    /**
     * Base of the interrupt controller (PIC) register bank (D33), at the very end
     * of the device MMIO aperture. The PIC holds the interrupt latch/mask and
     * exposes it as MMIO, so both the BIOS (during POST) and a program can enable
     * a line and raise/acknowledge it. The interconnect decodes it before ROM/RAM.
     */
    public static final int DEVICE_PIC_BASE = DEVICE_MMIO_BASE + 0xF0;     // 0x0FF0
    public static final int DEVICE_PIC_SIZE = 0x10;                        // 0x0FF0-0x0FFF

    // ===== Text screen (the BIOS may reconfigure it at runtime) =====

    /**
     * Dedicated page for the text screen window: the last page of user RAM. The
     * framebuffer must live entirely inside it, so the shadowing is aligned to a
     * page boundary instead of splitting another page in half. The page stays
     * mapped R/W as user RAM: when the screen is disabled, programs get all
     * 4 KB back as ordinary storage.
     */
    public static final int DISPLAY_WINDOW_BASE = 0xF000;
    public static final int DISPLAY_WINDOW_SIZE = PAGE; // 4096

    /** Default framebuffer base for the screen window (start of its page). */
    public static final int DISPLAY_DEFAULT_BASE = DISPLAY_WINDOW_BASE;
    public static final int DISPLAY_DEFAULT_COLS = 80;
    public static final int DISPLAY_DEFAULT_ROWS = 25;

    /** Page index of a virtual address. */
    public static int pageOf(int vaddr) {
        return vaddr >>> PAGE_SHIFT;
    }
}
