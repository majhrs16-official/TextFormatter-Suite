/**
 * iFlow VM Assembler - Two-pass assembler for .flowasm to .flow binary
 */
import {
  Opcode,
  Register,
  INSTRUCTION_SIZES,
  REGISTER_NAMES,
  ServiceId,
  Scope,
  PropertyId,
  Decision,
  ParsedInstruction,
  Operand,
  AssemblerOptions,
  AssemblerResult,
  Diagnostic,
  ProgramImage,
  PROGRAM_VERSIONS,
  Symbol as SymbolType,
  isGPR,
} from './types';

interface LabelDef {
  name: string;
  address: number;
  line: number;
}

class Assembler {
  private options: AssemblerOptions;
  private diagnostics: Diagnostic[] = [];
  private labels = new Map<string, LabelDef>();
  private instructions: ParsedInstruction[] = [];
  private symbols = new Map<string, number>();
  private capabilities = new Set<string>();
  private currentAddress = 0;
  private currentLine = 0;
  private currentFile = '<input>';
  private currentSection = 'code';
  private constantPool: number[] = [];
  private firmwareEnabled = true;
  /** Address of the BIOS reset routine ({@code fw_reset}); -1 when firmware is off. */
  private biosEntry = -1;
  /** Address of the user's entry point (firmware on), resolved during secondPass. */
  private userEntryAddress = -1;

  constructor(options: AssemblerOptions = {}) {
    this.options = options;
  }

  async assemble(source: string, fileName = '<input>'): Promise<AssemblerResult> {
    this.reset();
    this.currentFile = fileName;
    this.currentAddress = this.userOrigin();

    // First pass: parse instructions and collect labels
    this.firstPass(source);

    // Check for errors from first pass
    const firstPassErrors = this.diagnostics.filter(d => d.severity === 'error');
    if (firstPassErrors.length > 0) {
      return this.createResultSync(new Uint8Array(0));
    }

    // Second pass: resolve labels and generate bytecode
    const code = this.secondPass();

    // Check for errors from second pass
    const secondPassErrors = this.diagnostics.filter(d => d.severity === 'error');
    if (secondPassErrors.length > 0) {
      return this.createResultSync(new Uint8Array(0));
    }

    return this.createResult(new Uint8Array(code));
  }

  private reset() {
    this.diagnostics = [];
    this.labels.clear();
    this.instructions = [];
    this.symbols.clear();
    this.capabilities.clear();
    this.constantPool = [];
    this.firmwareEnabled = this.options.firmware ?? true;
    this.biosEntry = -1;
    this.userEntryAddress = -1;
    this.currentAddress = this.userOrigin();
    this.currentLine = 0;
    this.currentSection = 'code';
    this.constantPool = [];
  }

  private firstPass(source: string) {
    const lines = source.split('\n');
    this.currentAddress = this.userOrigin();

    for (let i = 0; i < lines.length; i++) {
      this.currentLine = i + 1;
      const line = lines[i];

      // Remove comments and trim
      const commentIndex = line.indexOf(';');
      const cleanLine = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
      const trimmed = cleanLine.trim();

      if (!trimmed) continue;

      // Handle directives
      if (trimmed.startsWith('.')) {
        this.handleDirective(trimmed);
        continue;
      }

      // Check for label
      const labelMatch = trimmed.match(/^([a-zA-Z_][a-zA-Z0-9_]*):/);
      let instructionPart = trimmed;
      if (labelMatch) {
        const labelName = labelMatch[1];
        this.defineLabel(labelName, this.currentAddress, this.currentLine);
        instructionPart = trimmed.substring(labelMatch[0].length).trim();
      }

      if (instructionPart) {
        this.parseInstruction(instructionPart);
      }
    }
  }

  private secondPass(): number[] {
    const code: number[] = [];

    // Prepend firmware ROM if enabled (occupies 0x0000-0x0FFF). User code was
    // already laid out from userOrigin() (>= 0x1000), so instruction addresses
    // match the labels/entry point and the gap is filled with padding below.
    // The ROM's reset routine is the machine's entry point (D32): it chains to
    // the user entry, which the assembler embeds as a relative jump.
    if (this.firmwareEnabled) {
      this.userEntryAddress = this.resolveUserEntry();
      const firmware = this.generateFirmware(this.userEntryAddress);
      this.biosEntry = firmware.reset;
      code.push(...firmware.rom);
    }

    for (const instr of this.instructions) {
      // Insert padding bytes to reach instruction's address (materialize .origin/.align gaps)
      while (code.length < instr.address) {
        code.push(0x00); // NOP/padding
      }

      // Emit opcode
      code.push(instr.opcode);
      
      // Emit operands
      this.emitOperands(instr, code);
    }

    // Validate user code size (excluding firmware)
    const userCodeStart = this.userOrigin();
    const userCodeSize = code.length - userCodeStart;
    const MAX_USER_CODE_SIZE = 4096; // 4KB = 1 page
    if (userCodeSize > MAX_USER_CODE_SIZE) {
      this.addDiagnostic('error', 
        `User code too large: ${userCodeSize} bytes (max ${MAX_USER_CODE_SIZE} bytes / 1 page). ` +
        `Code must fit in a single 4KB page.`,
        0, 0);
    }

    return code;
  }

  /**
   * Generates the firmware ROM (4KB at 0x0000-0x0FFF).
   * Contains: vector table, service constants, string/memory utils, host wrappers
   * and the active BIOS reset routine {@code fw_reset} (D32).
   * The final 256 bytes (0x0F00-0x0FFF) are reserved for the device control
   * block (MMIO registers of the text screen, see VmMemoryMap.DEVICE_MMIO_BASE).
   *
   * @param userEntry absolute address the reset routine chains to
   * @returns the ROM bytes and the address of the {@code fw_reset} entry
   */
  private generateFirmware(userEntry: number): { rom: number[]; reset: number } {
    const fw: number[] = [];

    // ===== BIOS / device addresses (mirror VmMemoryMap; the assembler is a
    // separate project and cannot import the Java constants) =====
    const MMIO_DISPLAY_BASE = 0x0F00;  // screen register bank
    const DEVICE_NVRAM_BASE = 0x0F10;  // BIOS NVRAM scratch
    const BOOT_MAGIC        = 0xB105;  // "booted" marker stored in NVRAM
    const DATA_BASE         = 0x3000;  // volatile data/heap region
    const DATA_WORDS        = 4096;    // 2 pages / 2 bytes per word
    const DISPLAY_BASE      = 0xE000;  // default framebuffer window
    const DISPLAY_COLS      = 80;
    const DISPLAY_ROWS      = 25;

    // ===== Size-correct emitter with label/fixup support =====
    const u8 = (b: number) => { fw.push(b & 0xFF); };
    const u16be = (v: number) => { fw.push((v >> 8) & 0xFF, v & 0xFF); };
    const pack = (a: number, b: number) => u8(((a & 0x0F) << 4) | (b & 0x0F));
    const li = (rd: number, imm: number) => { u8(Opcode.LI); u8(rd); u16be(imm & 0xFFFF); };
    const mov = (rd: number, rs: number) => { u8(Opcode.MOV); pack(rd, rs); };
    const rr = (op: Opcode, rd: number, rs: number) => { u8(op); pack(rd, rs); };
    const ldbi = (rd: number, rs: number) => { u8(Opcode.LDBI); pack(rd, rs); };
    const ldwi = (rd: number, rs: number) => { u8(Opcode.LDWI); pack(rd, rs); };
    const stbi = (rv: number, ra: number) => { u8(Opcode.STBI); pack(rv, ra); };
    const stwi = (rv: number, ra: number) => { u8(Opcode.STWI); pack(rv, ra); };
    const ret = () => u8(Opcode.RET);
    const host = () => { u8(Opcode.HOST); u8(0); };
    const labelAddr = new Map<string, number>();
    const mark = (name: string) => { labelAddr.set(name, fw.length); };
    const fixups: { at: number; label: string }[] = [];
    // BR16: the offset is relative to the end of the 3-byte instruction (at + 2).
    const br = (op: Opcode, target: string) => {
      u8(op);
      fixups.push({ at: fw.length, label: target });
      u8(0); u8(0);
    };

    // ===== Vector Table (0x0000-0x003F) - Fixed addresses for firmware functions =====
    // Each entry is a 16-bit address (little-endian)
    const vectors: [string, number][] = [
      ['fw_version',        0x0100],  // Firmware version
      ['fw_strlen',         0x0000],  // Filled below
      ['fw_strcpy',         0x0000],
      ['fw_strcmp',         0x0000],
      ['fw_memcpy',         0x0000],
      ['fw_memset',         0x0000],
      ['fw_call_host',      0x0000],  // Generic HOST caller: R0=svc, R1=arg, R2=opt, R3=len
      ['fw_prop_read',      0x0000],  // R1=scope, R2=prop_id, R3=out_buf, R4=len
      ['fw_prop_write',     0x0000],  // R1=scope, R2=prop_id, R3=src_buf, R4=len
      ['fw_route_decide',   0x0000],  // R1=decision, R2=buf, R3=len
      ['fw_perm_check',     0x0000],  // R1=buf, R2=len
      ['fw_state_read',     0x0000],  // R1=key, R2=out_buf, R3=len
      ['fw_state_write',    0x0000],  // R1=key, R2=src_buf, R3=len
      ['fw_channel_resolve',0x0000],  // R1=name_buf, R2=len
    ];

    // Placeholder vector entries (filled in with real addresses once the
    // functions below have been laid out).
    for (let i = 0; i < vectors.length; i++) {
      fw.push(0x00, 0x00);
    }

    // ===== Constants / Service IDs (0x0040-0x00FF) =====
    while (fw.length < 0x0040) fw.push(0x00); // pad to 0x0040

    // Service IDs
    this.emitConst(fw, 'SERVICE_PROPERTY_READ',  0x0100);
    this.emitConst(fw, 'SERVICE_PROPERTY_WRITE', 0x0101);
    this.emitConst(fw, 'SERVICE_PROPERTY_EXISTS',0x0102);
    this.emitConst(fw, 'SERVICE_PERM_CHECK',     0x0200);
    this.emitConst(fw, 'SERVICE_PERM_QUERY',     0x0201);
    this.emitConst(fw, 'SERVICE_ROUTE_DECISION', 0x0300);
    this.emitConst(fw, 'SERVICE_ROUTE_REDIRECT', 0x0301);
    this.emitConst(fw, 'SERVICE_ROUTE_RATE_LIMIT',0x0302);
    this.emitConst(fw, 'SERVICE_TRANSFORM_APPLY',0x0400);
    this.emitConst(fw, 'SERVICE_STATE_READ',     0x0500);
    this.emitConst(fw, 'SERVICE_STATE_WRITE',    0x0501);
    this.emitConst(fw, 'SERVICE_CHANNEL_RESOLVE',0x0600);
    this.emitConst(fw, 'SERVICE_CHANNEL_POLICY', 0x0601);
    this.emitConst(fw, 'SERVICE_MESSAGE_CLONE',  0x0700);
    this.emitConst(fw, 'SERVICE_MESSAGE_ID',     0x0701);

    // Scopes
    this.emitConst(fw, 'SCOPE_MESSAGE',     0x01);
    this.emitConst(fw, 'SCOPE_EMITTER',     0x02);
    this.emitConst(fw, 'SCOPE_RECIPIENT',   0x03);
    this.emitConst(fw, 'SCOPE_CHANNEL',     0x04);
    this.emitConst(fw, 'SCOPE_SERVER',      0x05);

    // Property IDs (message scope)
    this.emitConst(fw, 'PROP_CONTENT',       0x0010);
    this.emitConst(fw, 'PROP_CHANNEL',       0x0011);
    this.emitConst(fw, 'PROP_DIRECTION',     0x0012);
    this.emitConst(fw, 'PROP_TYPE',          0x0013);
    this.emitConst(fw, 'PROP_LANG_SOURCE',   0x0014);
    this.emitConst(fw, 'PROP_LANG_TARGET',   0x0015);
    this.emitConst(fw, 'PROP_COLOR_MODE',    0x0016);
    this.emitConst(fw, 'PROP_FORMAT_PAPI',   0x0017);
    this.emitConst(fw, 'PROP_SLEEP_MILLIS',  0x0018);
    this.emitConst(fw, 'PROP_SOUNDS',        0x0019);
    this.emitConst(fw, 'PROP_CANCELLED',     0x001A);
    this.emitConst(fw, 'PROP_PROCESSED',     0x001B);
    this.emitConst(fw, 'PROP_TRANSLATE',     0x001C);

    // Actor properties. propId is 8-bit and namespaced by scope (HOST ABI v2,
    // D2): the numeric id only makes sense together with the scope byte. Actor
    // and channel share ids 0x00-0x03; the scope (EMITTER/RECIPIENT vs CHANNEL)
    // disambiguates them.
    this.emitConst(fw, 'PROP_ACTOR_NAME',    0x00);
    this.emitConst(fw, 'PROP_ACTOR_UUID',    0x01);
    this.emitConst(fw, 'PROP_ACTOR_LANG',    0x02);
    this.emitConst(fw, 'PROP_ACTOR_WORLD',   0x03);

    // Channel properties
    this.emitConst(fw, 'PROP_CHAN_NAME',     0x00);
    this.emitConst(fw, 'PROP_CHAN_RATE',     0x01);
    this.emitConst(fw, 'PROP_CHAN_SEND_POL', 0x02);
    this.emitConst(fw, 'PROP_CHAN_RECV_POL', 0x03);

    // Decisions
    this.emitConst(fw, 'DECISION_LOG',         0);
    this.emitConst(fw, 'DECISION_DROP',        1);
    this.emitConst(fw, 'DECISION_REJECT',      2);
    this.emitConst(fw, 'DECISION_REDIRECT',    3);
    this.emitConst(fw, 'DECISION_CHAN_REDIR',  4);
    this.emitConst(fw, 'DECISION_RATE_LIMIT',  5);
    this.emitConst(fw, 'DECISION_CONTINUE',    6);

    // ===== Firmware Functions (starting at 0x0100) =====
    // Align to 0x0100
    while (fw.length < 0x0100) fw.push(0x00);

    // fw_version at 0x0100 (data word: major.minor)
    fw.push(0x01, 0x00);

    // ---- fw_strlen: R1 = src addr, returns R0 = length ----
    mark('fw_strlen');
    li(0, 0);                    // R0 = 0 (length)
    li(2, 1);                    // R2 = 1 (increment)
    mark('fw_strlen_loop');
    ldbi(3, 1);                  // R3 = *src  (sets Z when NUL)
    br(Opcode.JZ, 'fw_strlen_done');
    rr(Opcode.ADD, 0, 2);        // R0++
    rr(Opcode.ADD, 1, 2);        // src++
    br(Opcode.JMP, 'fw_strlen_loop');
    mark('fw_strlen_done');
    ret();

    // ---- fw_strcpy: R1 = dst, R2 = src, returns R0 = bytes copied ----
    mark('fw_strcpy');
    li(0, 0);                    // R0 = count
    li(4, 1);                    // R4 = 1 (increment)
    mark('fw_strcpy_loop');
    ldbi(3, 2);                  // R3 = *src (sets Z at NUL)
    stbi(3, 1);                  // *dst = R3
    br(Opcode.JZ, 'fw_strcpy_done');
    rr(Opcode.ADD, 0, 4);
    rr(Opcode.ADD, 1, 4);
    rr(Opcode.ADD, 2, 4);
    br(Opcode.JMP, 'fw_strcpy_loop');
    mark('fw_strcpy_done');
    ret();

    // ---- fw_strcmp: R1 = a, R2 = b, returns R0 = 0 if equal else sign(a-b) ----
    mark('fw_strcmp');
    mark('fw_strcmp_loop');
    ldbi(3, 1);                  // R3 = *a
    ldbi(4, 2);                  // R4 = *b
    rr(Opcode.CMP, 3, 4);
    br(Opcode.JNZ, 'fw_strcmp_diff');
    li(5, 0);
    rr(Opcode.CMP, 3, 5);        // *a == 0 ?  (equal strings)
    br(Opcode.JZ, 'fw_strcmp_eq');
    li(6, 1);
    rr(Opcode.ADD, 1, 6);
    rr(Opcode.ADD, 2, 6);
    br(Opcode.JMP, 'fw_strcmp_loop');
    mark('fw_strcmp_diff');
    mov(0, 3);
    rr(Opcode.SUB, 0, 4);        // R0 = *a - *b
    ret();
    mark('fw_strcmp_eq');
    li(0, 0);
    ret();

    // ---- fw_memcpy: R1 = dst, R2 = src, R3 = len, returns R0 = len ----
    mark('fw_memcpy');
    mov(0, 3);                   // R0 = len
    li(7, 0);
    rr(Opcode.CMP, 3, 7);
    br(Opcode.JZ, 'fw_memcpy_done');
    li(5, 1);
    mark('fw_memcpy_loop');
    ldbi(4, 2);
    stbi(4, 1);
    rr(Opcode.ADD, 1, 5);
    rr(Opcode.ADD, 2, 5);
    rr(Opcode.SUB, 3, 5);        // len-- (sets Z at 0)
    br(Opcode.JNZ, 'fw_memcpy_loop');
    mark('fw_memcpy_done');
    ret();

    // ---- fw_memset: R1 = dst, R2 = byte, R3 = len, returns R0 = len ----
    mark('fw_memset');
    mov(0, 3);
    li(7, 0);
    rr(Opcode.CMP, 3, 7);
    br(Opcode.JZ, 'fw_memset_done');
    li(5, 1);
    mark('fw_memset_loop');
    stbi(2, 1);                  // *dst = byte (R2)
    rr(Opcode.ADD, 1, 5);
    rr(Opcode.SUB, 3, 5);
    br(Opcode.JNZ, 'fw_memset_loop');
    mark('fw_memset_done');
    ret();

    // ---- HOST wrappers ----
    // fw_call_host: R0=svc, R1=options, R2=base, R3=len (pure passthrough)
    mark('fw_call_host');
    host();
    ret();

    // fw_prop_read: R1=scope, R2=propId, R3=outBuf, R4=len
    mark('fw_prop_read');
    mov(5, 1);
    li(6, 8);
    rr(Opcode.SHL, 5, 6);        // R5 = scope << 8
    rr(Opcode.OR, 5, 2);         // R5 |= propId
    mov(2, 3);                   // base = outBuf
    mov(3, 4);                   // length = len
    mov(1, 5);                   // options
    li(0, 0x0100);
    host();
    ret();

    // fw_prop_write: R1=scope, R2=propId, R3=srcBuf, R4=len
    mark('fw_prop_write');
    mov(5, 1);
    li(6, 8);
    rr(Opcode.SHL, 5, 6);
    rr(Opcode.OR, 5, 2);
    mov(2, 3);
    mov(3, 4);
    mov(1, 5);
    li(0, 0x0101);
    host();
    ret();

    // fw_perm_check: R1=buf ("actor\0perm"), R2=len
    mark('fw_perm_check');
    mov(5, 1);
    mov(3, 2);                   // length = len
    mov(2, 5);                   // base = buf
    li(1, 0);                    // options = 0
    li(0, 0x0200);
    host();
    ret();

    // fw_route_decide: R1=decision, R2=buf, R3=len
    mark('fw_route_decide');
    li(0, 0x0300);
    host();
    ret();

    // fw_state_read: R1=key, R2=outBuf, R3=len
    mark('fw_state_read');
    li(0, 0x0500);
    host();
    ret();

    // fw_state_write: R1=key, R2=srcBuf, R3=len
    mark('fw_state_write');
    li(0, 0x0501);
    host();
    ret();

    // fw_channel_resolve: R1=buf, R2=len (service writes the id back to buf)
    mark('fw_channel_resolve');
    mov(5, 1);
    mov(3, 2);
    mov(2, 5);
    li(1, 0);
    li(0, 0x0600);
    host();
    ret();

    // ===== BIOS reset / POST routine (D32, entry point) =====
    // The machine resets into this routine. On a cold boot (the NVRAM boot flag
    // is clear) it performs POST: it clears the volatile data region word by word
    // and configures the text screen through the device MMIO registers. It never
    // touches the program's general-purpose RAM, which persists across messages
    // (D30), and it marks the boot as done so later messages take the warm path.
    // It then chains to the user entry point (a relative jump patched below).
    mark('fw_reset');

    // Cold-boot detection: R3 = NVRAM boot flag.
    li(1, DEVICE_NVRAM_BASE);
    ldwi(3, 1);
    li(2, BOOT_MAGIC);
    rr(Opcode.CMP, 3, 2);
    br(Opcode.JZ, 'fw_reset_warm');   // already booted -> chain

    // ---- Cold POST ----
    // Clear volatile data/heap (0x3000-0x4FFF), one word per iteration.
    li(1, DATA_BASE);
    li(2, DATA_WORDS);
    li(4, 0);                         // zero word (also the loop's compare value)
    li(5, 2);                         // word stride
    li(6, 1);                         // decrement
    mark('fw_reset_clear');
    stwi(4, 1);
    rr(Opcode.ADD, 1, 5);
    rr(Opcode.SUB, 2, 6);
    rr(Opcode.CMP, 2, 4);
    br(Opcode.JNZ, 'fw_reset_clear');

    // Configure the text screen: framebuffer base, geometry and enable.
    li(1, MMIO_DISPLAY_BASE + 0x00); li(2, DISPLAY_BASE);       stwi(2, 1);
    li(1, MMIO_DISPLAY_BASE + 0x02); li(2, DISPLAY_COLS);       stwi(2, 1);
    li(1, MMIO_DISPLAY_BASE + 0x04); li(2, DISPLAY_ROWS);       stwi(2, 1);
    li(1, MMIO_DISPLAY_BASE + 0x06); li(2, 1);                  stwi(2, 1); // CTRL: enable

    // Mark the boot as done in NVRAM.
    li(1, DEVICE_NVRAM_BASE); li(2, BOOT_MAGIC);                stwi(2, 1);

    mark('fw_reset_warm');
    // Chain to the user entry point. The 16-bit offset is relative to the end of
    // this 3-byte instruction and is patched here (the user entry is known).
    const chainAt = fw.length;
    u8(Opcode.JMP);
    u8(0x00);
    u8(0x00);
    const chainRel = (userEntry - (chainAt + 3)) & 0xFFFF;
    fw[chainAt + 1] = (chainRel >> 8) & 0xFF;
    fw[chainAt + 2] = chainRel & 0xFF;

    // ===== Resolve relative branches =====
    for (const fx of fixups) {
      const target = labelAddr.get(fx.label);
      if (target === undefined) continue; // unreachable: all labels are defined above
      const offset = (target - (fx.at + 2)) & 0xFFFF;
      fw[fx.at] = (offset >> 8) & 0xFF;
      fw[fx.at + 1] = offset & 0xFF;
    }

    // ===== Device control block reservation (D31) =====
    // The last 256 bytes of the BIOS ROM page (0x0F00-0x0FFF) host the
    // memory-mapped device control registers (the text screen's MMIO bank; see
    // VmMemoryMap.DEVICE_MMIO_BASE). The interconnect decodes them before the
    // ROM, so whatever sits there is shadowed; keeping firmware code out of the
    // range guarantees nothing executable is ever hidden.
    const DEVICE_MMIO_BASE = 0x0F00;
    if (fw.length > DEVICE_MMIO_BASE) {
      this.addDiagnostic('error',
        `Firmware code (${fw.length} bytes) overruns the device control block reserved at 0x${DEVICE_MMIO_BASE.toString(16).toUpperCase()}`,
        0, 0);
    }

    // Pad to 4KB (0x1000)
    while (fw.length < 0x1000) fw.push(0x00);

    // ===== Fill the vector table and register the equates =====
    for (let i = 0; i < vectors.length; i++) {
      const name = vectors[i][0];
      const addr = name === 'fw_version' ? 0x0100 : (labelAddr.get(name) ?? 0);
      const off = 2 * i;
      fw[off] = addr & 0xFF;            // little-endian data word
      fw[off + 1] = (addr >> 8) & 0xFF;
      // Expose the entry point as an equate so user code can `CALL fw_strlen`.
      this.defineEquate(name, addr);
    }

    return { rom: fw, reset: labelAddr.get('fw_reset')! };
  }

  /**
   * Emits a firmware ROM constant.
   * <p>
   * The constant is registered as an <em>equate</em> (name &rarr; value), not as a
   * storage address: {@code LI R0, SERVICE_PROPERTY_READ} must load {@code 0x0100},
   * not the ROM offset where the value happens to live. A program that declares the
   * same name wins, so ROM constants never collide with user {@code .const}/{@code .equ}
   * definitions.
   * </p>
   */
  private emitConst(fw: number[], name: string, value: number) {
    this.defineEquate(name, value);
    fw.push(value & 0xFF, (value >> 8) & 0xFF);
  }

  /** Registers a ROM-provided equate; an existing (user) definition is kept. */
  private defineEquate(name: string, value: number) {
    if (this.labels.has(name)) return; // user definition wins
    this.labels.set(name, { name, address: value, line: 0 });
    this.symbols.set(name, value);
  }

  /**
   * Effective origin for user code. When the firmware ROM is prepended it occupies
   * 0x0000-0x0FFF, so user code starts at page 1 (0x1000) unless the caller asked
   * for a higher origin. Labels, entry point and emitted addresses all use this base
   * so they stay consistent with where the code is actually placed.
   */
  private userOrigin(): number {
    const origin = this.options.origin || 0;
    return this.firmwareEnabled ? Math.max(origin, 0x1000) : origin;
  }

  private handleDirective(line: string) {
    const parts = line.split(/\s+/);
    const directive = parts[0].toLowerCase();

    switch (directive) {
      case '.section':
      case '.seg':
        this.currentSection = parts[1] || 'code';
        break;

      case '.entry':
        this.options.entryPoint = parts[1];
        break;

      case '.origin':
      case '.org': {
        const newAddr = this.parseNumber(parts[1] || '0');
        if (newAddr < this.currentAddress) {
          this.addDiagnostic('error', `.origin cannot move backwards (current: 0x${this.currentAddress.toString(16)}, requested: 0x${newAddr.toString(16)})`, this.currentLine, 0);
        } else if (newAddr < (this.options.origin || 0)) {
          this.addDiagnostic('error', `.origin cannot be before program origin (0x${(this.options.origin || 0).toString(16)})`, this.currentLine, 0);
        } else {
          this.currentAddress = newAddr;
        }
        break;
      }

      case '.capability':
      case '.cap':
        if (parts[1]) {
          this.capabilities.add(parts[1]);
        }
        break;

      case '.firmware':
        if (parts[1]) {
          this.firmwareEnabled = parts[1].toLowerCase() !== 'off' && parts[1] !== '0';
          // Only safe to re-base while no instruction has been laid out yet.
          if (this.instructions.length === 0) {
            this.currentAddress = this.userOrigin();
          }
        }
        break;

      case '.align':
        const align = this.parseNumber(parts[1] || '2');
        if (!Number.isInteger(align) || align <= 0) {
          this.addDiagnostic('error', `.align requires positive integer, got: ${parts[1]}`, this.currentLine, 0);
        } else if ((align & (align - 1)) !== 0) {
          this.addDiagnostic('warning', `.align value should be power of two: ${align}`, this.currentLine, 0);
          while (this.currentAddress % align !== 0) {
            this.currentAddress++;
          }
        } else {
          while (this.currentAddress % align !== 0) {
            this.currentAddress++;
          }
        }
        break;

      case '.const':
        if (parts.length >= 3) {
          const name = parts[1];
          const value = this.parseNumber(parts[2]);
          // Define as an equate for use in instructions (labels) and diagnostics.
          this.defineLabel(name, value, this.currentLine);
          // Also add to the constant pool for the binary's constants section.
          this.constantPool.push(value);
        }
        break;

      case '.equ':
      case '.set':
      case '=':
        if (parts.length >= 3) {
          const name = parts[1];
          const value = this.parseNumber(parts[2]);
          this.defineLabel(name, value, this.currentLine);
        }
        break;

      case '.end':
        // End of assembly - ignore
        break;

      default:
        this.addDiagnostic('warning', `Unknown directive: ${directive}`, this.currentLine, 0);
    }
  }

  private defineLabel(name: string, address: number, line: number) {
    if (this.labels.has(name)) {
      this.addDiagnostic('error', `Duplicate label: ${name}`, line, 0);
    } else {
      this.labels.set(name, { name, address, line });
      this.symbols.set(name, address);
    }
  }

  private parseInstruction(line: string) {
    const parts = this.tokenize(line);
    if (parts.length === 0) return;

    const mnemonic = parts[0].toUpperCase();
    const opcode = this.getOpcode(mnemonic);

    if (opcode === undefined) {
      this.addDiagnostic('error', `Unknown instruction: ${mnemonic}`, this.currentLine, 0);
      return;
    }

    const operands = this.parseOperands(parts.slice(1), opcode);
    
    // Validate operands
    this.validateOperands(opcode, operands, parts.slice(1).length);
    
    const size = INSTRUCTION_SIZES[opcode] || this.calculateSize(opcode, operands);

    const instruction: ParsedInstruction = {
      opcode,
      operands,
      address: this.currentAddress,
      size,
      line: this.currentLine,
      source: line,
    };

    this.instructions.push(instruction);
    this.currentAddress += size;
  }

  private validateOperands(opcode: Opcode, operands: Operand[], providedCount: number) {
    const expected = this.getExpectedOperands(opcode);
    if (!expected) return;

    // Check operand count
    if (providedCount < expected.min) {
      this.addDiagnostic('error', `${Opcode[opcode]} requires at least ${expected.min} operand(s), got ${providedCount}`, this.currentLine, 0);
      return;
    }
    if (providedCount > expected.max) {
      this.addDiagnostic('error', `${Opcode[opcode]} accepts at most ${expected.max} operand(s), got ${providedCount}`, this.currentLine, 0);
      return;
    }

    // Validate each operand
    for (let i = 0; i < operands.length; i++) {
      const op = operands[i];
      const exp = expected.types[i];
      if (!exp) continue;

      if (exp === 'gpr' && op.type === 'reg') {
        if (!isGPR(op.value)) {
          this.addDiagnostic('error', `Operand ${i + 1}: ${Opcode[opcode]} requires general-purpose register (R0-R7), got ${op.type}`, this.currentLine, 0);
        }
      } else if (exp === 'any_reg' && op.type === 'reg') {
        // Any register including control registers
      } else if (exp === 'imm8' && (op.type === 'imm8' || op.type === 'imm16')) {
        const val = op.rawValue !== undefined ? op.rawValue : op.value;
        if (val < -128 || val > 255) {
          this.addDiagnostic('error', `Operand ${i + 1}: immediate out of 8-bit range (-128..255): ${val}`, this.currentLine, 0);
        }
      } else if (exp === 'imm16' && (op.type === 'imm8' || op.type === 'imm16')) {
        const val = op.rawValue !== undefined ? op.rawValue : op.value;
        if (val < -32768 || val > 65535) {
          this.addDiagnostic('error', `Operand ${i + 1}: immediate out of 16-bit range: ${val}`, this.currentLine, 0);
        }
      } else if (exp === 'imm16' && op.type === 'label') {
        // Symbols/labels resolve to their address in getImm16Operand (masked to
        // 16 bits there), so there is no static value to range-check here.
        // Mirrors the 'vaddr' branch, which already accepts labels.
      } else if (exp === 'vaddr' && (op.type === 'imm16' || op.type === 'label' || op.type === 'vaddr')) {
        // Valid
      } else if (exp === 'label' && op.type === 'label') {
        // Valid
      } else if (exp === 'reg' && op.type === 'reg') {
        // Valid
      } else if (op.type !== exp) {
        this.addDiagnostic('error', `Operand ${i + 1}: ${Opcode[opcode]} expects ${exp}, got ${op.type}`, this.currentLine, 0);
      }
    }
  }

  private getExpectedOperands(opcode: Opcode): { min: number; max: number; types: string[] } | null {
    switch (opcode) {
      case Opcode.LI: return { min: 2, max: 2, types: ['gpr', 'imm16'] };
      case Opcode.MOV: return { min: 2, max: 2, types: ['gpr', 'gpr'] };
      case Opcode.LDB: case Opcode.LDW: case Opcode.STB: case Opcode.STW:
        return { min: 2, max: 2, types: ['gpr', 'vaddr'] };
      case Opcode.LDBI: case Opcode.LDWI: case Opcode.STBI: case Opcode.STWI:
        return { min: 2, max: 2, types: ['gpr', 'gpr'] };
      case Opcode.COPY: case Opcode.MOVE:
        return { min: 3, max: 3, types: ['vaddr', 'vaddr', 'imm16'] };
      case Opcode.ADD: case Opcode.SUB: case Opcode.MUL: case Opcode.DIV: case Opcode.MOD:
      case Opcode.AND: case Opcode.OR: case Opcode.XOR:
      case Opcode.SHL: case Opcode.SHR: case Opcode.SAR:
      case Opcode.CMP: case Opcode.CMPU:
        return { min: 2, max: 2, types: ['gpr', 'gpr'] };
      case Opcode.NOT: return { min: 1, max: 1, types: ['gpr'] };
      case Opcode.JMP: case Opcode.JZ: case Opcode.JNZ: case Opcode.JC: case Opcode.JV: case Opcode.CALL:
        return { min: 1, max: 1, types: ['label'] };
      case Opcode.RET: return { min: 0, max: 0, types: [] };
      case Opcode.HOST: return { min: 0, max: 0, types: [] };
      case Opcode.HALT: return { min: 0, max: 0, types: [] };
      case Opcode.TRAP: return { min: 1, max: 1, types: ['imm8'] };
      default: return null;
    }
  }

  private tokenize(line: string): string[] {
    // Split by comma or whitespace, keeping quoted strings together
    // Also handle simple expressions like LABEL + OFFSET
    const tokens: string[] = [];
    let current = '';
    let inQuotes = false;
    let quoteChar = '';

    for (let i = 0; i < line.length; i++) {
      const char = line[i];

      if ((char === '"' || char === "'") && !inQuotes) {
        inQuotes = true;
        quoteChar = char;
        current += char;
      } else if (char === quoteChar && inQuotes) {
        inQuotes = false;
        current += char;
      } else if ((char === ',' || /\s/.test(char)) && !inQuotes) {
        if (current) {
          tokens.push(current);
          current = '';
        }
      } else if ((char === '+' || char === '-') && !inQuotes) {
        // Handle +/- as part of expression (e.g., LABEL+OFFSET)
        if (current && /[a-zA-Z0-9_]$/.test(current)) {
          current += char;
        } else {
          if (current) {
            tokens.push(current);
            current = '';
          }
          current += char;
        }
      } else {
        current += char;
      }
    }

    if (current) tokens.push(current);
    
    // Post-process: merge tokens like "LABEL+OFFSET" into single token
    const merged: string[] = [];
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i];
      if (i > 0 && (token === '+' || token === '-') && 
          /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(tokens[i-1]) &&
          i + 1 < tokens.length && this.isNumber(tokens[i+1])) {
        // Merge: previous_label + number
        merged[merged.length - 1] = tokens[i-1] + token + tokens[i+1];
        i++; // Skip the number token
      } else {
        merged.push(token);
      }
    }
    
    // Debug
    // console.error(`TOKENS: ${JSON.stringify(merged)}`);
    
    return merged.map(t => t.replace(/^,|,$/g, '').trim()).filter(t => t);
  }

  private getOpcode(mnemonic: string): Opcode | undefined {
    return Opcode[mnemonic as keyof typeof Opcode];
  }

  private parseOperands(tokens: string[], opcode: Opcode): Operand[] {
    const operands: Operand[] = [];

    for (const rawToken of tokens) {
      // Indirect operands may be written as [Rs]; brackets are not part of the
      // register name.
      const token = rawToken.replace(/[[\]]/g, '').trim();
      if (!token) continue;

      // Register
      if (REGISTER_NAMES[token.toUpperCase()] !== undefined) {
        operands.push({ type: 'reg', value: REGISTER_NAMES[token.toUpperCase()] });
        continue;
      }

      // Packed RR format (e.g., "R1,R2")
      if (token.includes(',')) {
        const [rd, rs] = token.split(',').map(s => s.trim().toUpperCase());
        if (REGISTER_NAMES[rd] !== undefined && REGISTER_NAMES[rs] !== undefined) {
          operands.push({ type: 'packed_rr', rd: REGISTER_NAMES[rd], rs: REGISTER_NAMES[rs] });
          continue;
        }
      }

      // Label reference (including LABEL+OFFSET expressions)
      if (/^[a-zA-Z_][a-zA-Z0-9_]*([+-]\d+)?$/.test(token) && !this.isNumber(token)) {
        operands.push({ type: 'label', name: token });
        continue;
      }

      // Number (immediate or address)
      const value = this.parseNumber(token);
      if (value >= -128 && value <= 255) {
        operands.push({ type: 'imm8', value: value & 0xFF, rawValue: value });
      } else {
        operands.push({ type: 'imm16', value: value & 0xFFFF, rawValue: value });
      }
    }

    return operands;
  }

  private isNumber(token: string): boolean {
    return /^0x[0-9a-fA-F]+$/.test(token) || /^\d+$/.test(token) || /^-?\d+$/.test(token);
  }

  private parseNumber(token: string): number {
    if (token.startsWith('0x') || token.startsWith('0X')) {
      return parseInt(token.substring(2), 16);
    }
    return parseInt(token, 10);
  }

  private calculateSize(opcode: Opcode, operands: Operand[]): number {
    return INSTRUCTION_SIZES[opcode] || 1;
  }

  private emitOperands(instr: ParsedInstruction, code: number[]) {
    const { opcode, operands } = instr;

    switch (opcode) {
      case Opcode.LI: { // LI rd, imm16
        const rd = this.getRegOperand(operands, 0);
        const imm = this.getImm16Operand(operands, 1);
        code.push(rd);
        code.push((imm >> 8) & 0xFF);
        code.push(imm & 0xFF);
        break;
      }

      case Opcode.MOV: { // MOV rd, rs (packed)
        const packed = this.getPackedRROperand(operands);
        if (packed) {
          code.push((packed.rd << 4) | packed.rs);
        } else {
          const rd = this.getRegOperand(operands, 0);
          const rs = this.getRegOperand(operands, 1);
          code.push((rd << 4) | rs);
        }
        break;
      }

      case Opcode.LDB:
      case Opcode.LDW:
      case Opcode.STB:
      case Opcode.STW: { // MEM rd/rs, vaddr16
        const reg = this.getRegOperand(operands, 0);
        const vaddr = this.getVaddrOperand(operands, 1);
        code.push(reg);
        code.push((vaddr >> 8) & 0xFF);
        code.push(vaddr & 0xFF);
        break;
      }

      case Opcode.LDBI:
      case Opcode.LDWI:
      case Opcode.STBI:
      case Opcode.STWI: { // RR (packed): Rd/Rv, Rs/Ra
        const packed = this.getPackedRROperand(operands);
        if (packed) {
          code.push((packed.rd << 4) | packed.rs);
        } else {
          const first = this.getRegOperand(operands, 0);
          const second = this.getRegOperand(operands, 1);
          code.push((first << 4) | second);
        }
        break;
      }

      case Opcode.COPY:
      case Opcode.MOVE: { // BLOCK srcVaddr16, dstVaddr16, len16 (8 bytes: opcode + src16 + dst16 + len16 + padding)
        const src = this.getVaddrOperand(operands, 0);
        const dst = this.getVaddrOperand(operands, 1);
        const len = this.getImm16Operand(operands, 2);
        code.push((src >> 8) & 0xFF, src & 0xFF);
        code.push((dst >> 8) & 0xFF, dst & 0xFF);
        code.push((len >> 8) & 0xFF, len & 0xFF);
        code.push(0x00); // Padding byte to make 8 bytes total (1 opcode + 7 operands = 8)
        break;
      }

      case Opcode.ADD:
      case Opcode.SUB:
      case Opcode.MUL:
      case Opcode.DIV:
      case Opcode.MOD:
      case Opcode.AND:
      case Opcode.OR:
      case Opcode.XOR:
      case Opcode.SHL:
      case Opcode.SHR:
      case Opcode.SAR:
      case Opcode.CMP:
      case Opcode.CMPU: { // RR rd, rs (packed)
        const packed = this.getPackedRROperand(operands);
        if (packed) {
          code.push((packed.rd << 4) | packed.rs);
        } else {
          const rd = this.getRegOperand(operands, 0);
          const rs = this.getRegOperand(operands, 1);
          code.push((rd << 4) | rs);
        }
        break;
      }

      case Opcode.NOT: { // R rd (2 bytes: opcode + reg)
        const rd = this.getRegOperand(operands, 0);
        code.push(rd & 0x0F);
        break;
      }

      case Opcode.JMP:
      case Opcode.JZ:
      case Opcode.JNZ:
      case Opcode.JC:
      case Opcode.JV:
      case Opcode.CALL: { // BR16 offset16
        const offset = this.getBranchOffset(operands, 0, instr.address, opcode);
        code.push((offset >> 8) & 0xFF);
        code.push(offset & 0xFF);
        break;
      }

      case Opcode.RET: { // R (1 byte: opcode only, no operand)
        break;
      }

      case Opcode.HOST: { // HOST (2 bytes: opcode + dummy)
        // Service ID is passed in R0, not in instruction
        // Emit dummy byte to match instructionSize(2)
        code.push(0);
        break;
      }

      case Opcode.HALT: {
        // No operands
        break;
      }

      case Opcode.TRAP: { // TRAP code8
        const code8 = this.getImm8Operand(operands, 0);
        code.push(code8);
        break;
      }
    }
  }

  private getRegOperand(operands: Operand[], index: number): number {
    const op = operands[index];
    if (op && op.type === 'reg') return op.value;
    return 0;
  }

  private getImm8Operand(operands: Operand[], index: number): number {
    const op = operands[index];
    if (op && (op.type === 'imm8' || op.type === 'imm16')) {
      const raw = op.rawValue !== undefined ? op.rawValue : op.value;
      return raw & 0xFF;
    }
    return 0;
  }

  private getImm16Operand(operands: Operand[], index: number): number {
    const op = operands[index];
    if (op && (op.type === 'imm16' || op.type === 'imm8')) {
      // Use the raw (unmasked) value: `value` is truncated to 8 bits for imm8
      // operands, so masking it again to 16 bits would zero-extend negatives
      // (e.g. `LI R1, -1` must emit 0xFFFF, not 0x00FF).
      const raw = op.rawValue !== undefined ? op.rawValue : op.value;
      return raw & 0xFFFF;
    }
    if (op && op.type === 'label') {
      // Handle LABEL+OFFSET or LABEL-OFFSET format
      const match = op.name.match(/^([a-zA-Z_][a-zA-Z0-9_]*)([+-]\d+)?$/);
      if (match) {
        const labelName = match[1];
        const offsetStr = match[2] || '0';
        const offset = parseInt(offsetStr, 10);
        const label = this.labels.get(labelName);
        if (label) return (label.address + offset) & 0xFFFF;
        this.addDiagnostic('error', `Undefined label: ${labelName}`, 0, 0);
        return 0;
      }
      // Fallback for plain labels
      const label = this.labels.get(op.name);
      if (label) return label.address & 0xFFFF;
      this.addDiagnostic('error', `Undefined label: ${op.name}`, 0, 0);
      return 0;
    }
    return 0;
  }

  private getVaddrOperand(operands: Operand[], index: number): number {
    return this.getImm16Operand(operands, index);
  }

  private getPackedRROperand(operands: Operand[]): { rd: number; rs: number } | null {
    for (const op of operands) {
      if (op.type === 'packed_rr') return { rd: op.rd, rs: op.rs };
    }
    return null;
  }

  private getBranchOffset(operands: Operand[], index: number, currentAddr: number, opcode: Opcode): number {
    const op = operands[index];
    if (!op) return 0;

    if (op.type === 'label') {
      const label = this.labels.get(op.name);
      if (label) {
        // Calculate relative offset from next instruction
        const nextAddr = currentAddr + (INSTRUCTION_SIZES[opcode] || 3);
        return (label.address - nextAddr) & 0xFFFF;
      }
      this.addDiagnostic('error', `Undefined label: ${op.name}`, 0, 0);
      return 0;
    }

    if (op.type === 'imm16' || op.type === 'imm8') {
      return (op.value - (currentAddr + 3)) & 0xFFFF;
    }

    return 0;
  }

  private createResultSync(program: Uint8Array): AssemblerResult {
    // For error cases, just return without computing hash
    const programImage: ProgramImage = {
      formatVersion: PROGRAM_VERSIONS.format,
      isaVersion: PROGRAM_VERSIONS.isa,
      minAbiVersion: PROGRAM_VERSIONS.minAbi,
      minFirmwareVersion: PROGRAM_VERSIONS.minFirmware,
      entryPoint: this.getEntryPointAddress(),
      code: program,
      constants: this.constantPool,
      capabilities: Array.from(this.capabilities),
      hash: new Uint8Array(32),
      debugInfo: this.options.debug ? this.generateDebugInfo() : undefined,
    };

    const symbolArray: SymbolType[] = [];
    for (const [name, address] of this.symbols) {
      symbolArray.push({ name, address, type: 'code' });
    }

    return {
      program: programImage,
      symbols: symbolArray,
      diagnostics: this.diagnostics,
    };
  }

  private async createResult(program: Uint8Array): Promise<AssemblerResult> {
    // Serialize program image without hash first
    const programImageNoHash: ProgramImage = {
      formatVersion: PROGRAM_VERSIONS.format,
      isaVersion: PROGRAM_VERSIONS.isa,
      minAbiVersion: PROGRAM_VERSIONS.minAbi,
      minFirmwareVersion: PROGRAM_VERSIONS.minFirmware,
      entryPoint: this.getEntryPointAddress(),
      code: program,
      constants: this.constantPool,
      capabilities: Array.from(this.capabilities),
      hash: new Uint8Array(32), // Placeholder
      debugInfo: this.options.debug ? this.generateDebugInfo() : undefined,
    };

    // Serialize to compute hash (excluding the hash field itself)
    const serializedNoHash = this.serializeProgramImageNoHash(programImageNoHash);
    const hash = await this.computeSHA256(serializedNoHash);

    // Now create final program image with correct hash
    const programImage: ProgramImage = {
      ...programImageNoHash,
      hash,
    };

    const symbolArray: SymbolType[] = [];
    for (const [name, address] of this.symbols) {
      symbolArray.push({ name, address, type: 'code' });
    }

    return {
      program: programImage,
      symbols: symbolArray,
      diagnostics: this.diagnostics,
    };
  }

  private serializeProgramImageNoHash(image: ProgramImage): Uint8Array {
    const parts: number[] = [];

    // Header (16 bytes)
    parts.push(image.formatVersion & 0xFF);
    parts.push(image.isaVersion & 0xFF);
    parts.push(image.minAbiVersion & 0xFF);
    parts.push(image.minFirmwareVersion & 0xFF);
    parts.push((image.entryPoint >> 8) & 0xFF);
    parts.push(image.entryPoint & 0xFF);
    parts.push((image.code.length >> 8) & 0xFF);
    parts.push(image.code.length & 0xFF);
    parts.push((image.constants.length >> 8) & 0xFF);
    parts.push(image.constants.length & 0xFF);
    parts.push(image.capabilities.length & 0xFF);
    parts.push(0, 0, 0, 0, 0);

    // Code
    parts.push(...Array.from(image.code));

    // Constants
    for (const c of image.constants) {
      parts.push((c >> 8) & 0xFF, c & 0xFF);
    }

    // Capabilities (null-terminated strings)
    for (const cap of image.capabilities) {
      for (const ch of cap) {
        parts.push(ch.charCodeAt(0));
      }
      parts.push(0);
    }

    return new Uint8Array(parts);
  }

  private async computeSHA256(data: Uint8Array): Promise<Uint8Array> {
    // Platform-agnostic SHA-256: Web Crypto API (browsers) or Node.js crypto
    if (typeof crypto !== 'undefined' && crypto.subtle) {
      // Browser / modern Node.js (16+)
      const buffer = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer;
      const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
      return new Uint8Array(hashBuffer);
    } else if (typeof process !== 'undefined' && process.versions?.node) {
      // Node.js fallback
      const { createHash } = await import('crypto');
      return new Uint8Array(createHash('sha256').update(Buffer.from(data)).digest());
    } else {
      throw new Error('SHA-256 not available: no Web Crypto API or Node.js crypto');
    }
  }

  /**
   * Resolves the user program's entry point from {@code .entry} (a label) or the
   * user origin. Emits a diagnostic when a named entry label is missing.
   */
  private resolveUserEntry(): number {
    if (this.options.entryPoint) {
      const label = this.labels.get(this.options.entryPoint);
      if (label) return label.address;
      this.addDiagnostic('error', `Entry point label not found: ${this.options.entryPoint}`, 0, 0);
    }
    return this.userOrigin();
  }

  /**
   * The machine's reset entry point. With the firmware ROM active the machine
   * resets into the BIOS ({@code fw_reset}, D32), which chains to the user
   * entry; a bare-metal image enters directly at the user entry.
   */
  private getEntryPointAddress(): number {
    if (this.firmwareEnabled && this.biosEntry >= 0) {
      return this.biosEntry;
    }
    if (this.userEntryAddress >= 0) {
      return this.userEntryAddress;
    }
    return this.resolveUserEntry();
  }

  private generateDebugInfo(): string {
    let info = '';
    for (const instr of this.instructions) {
      info += `${instr.address.toString(16).padStart(4, '0')}: ${instr.source}\n`;
    }
    for (const [name, address] of this.symbols) {
      info += `SYM ${name} = 0x${address.toString(16).padStart(4, '0')}\n`;
    }
    return info;
  }

  public serializeProgramImage(image: ProgramImage): Uint8Array {
    // Simple serialization: header + code + constants + capabilities
    const parts: number[] = [];

    // Header (16 bytes)
    parts.push(image.formatVersion & 0xFF);
    parts.push(image.isaVersion & 0xFF);
    parts.push(image.minAbiVersion & 0xFF);
    parts.push(image.minFirmwareVersion & 0xFF);
    parts.push((image.entryPoint >> 8) & 0xFF);
    parts.push(image.entryPoint & 0xFF);
    parts.push((image.code.length >> 8) & 0xFF);
    parts.push(image.code.length & 0xFF);
    parts.push((image.constants.length >> 8) & 0xFF);
    parts.push(image.constants.length & 0xFF);
    parts.push(image.capabilities.length & 0xFF);
    // 5 bytes padding
    parts.push(0, 0, 0, 0, 0);

    // Code
    parts.push(...Array.from(image.code));

    // Constants
    for (const c of image.constants) {
      parts.push((c >> 8) & 0xFF, c & 0xFF);
    }

    // Capabilities (null-terminated strings)
    for (const cap of image.capabilities) {
      for (const ch of cap) {
        parts.push(ch.charCodeAt(0));
      }
      parts.push(0);
    }

    // Hash (32 bytes)
    parts.push(...Array.from(image.hash));

    return new Uint8Array(parts);
  }

  private addDiagnostic(severity: 'error' | 'warning' | 'info', message: string, line: number, column: number) {
    this.diagnostics.push({
      severity,
      message,
      line: line || this.currentLine,
      column,
      file: this.currentFile,
    });
  }
}

export { Assembler };