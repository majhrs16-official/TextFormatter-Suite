// src/types.ts
var Opcode = /* @__PURE__ */ ((Opcode2) => {
  Opcode2[Opcode2["LI"] = 0] = "LI";
  Opcode2[Opcode2["MOV"] = 1] = "MOV";
  Opcode2[Opcode2["LDB"] = 2] = "LDB";
  Opcode2[Opcode2["LDW"] = 3] = "LDW";
  Opcode2[Opcode2["STB"] = 4] = "STB";
  Opcode2[Opcode2["STW"] = 5] = "STW";
  Opcode2[Opcode2["COPY"] = 6] = "COPY";
  Opcode2[Opcode2["MOVE"] = 7] = "MOVE";
  Opcode2[Opcode2["ADD"] = 32] = "ADD";
  Opcode2[Opcode2["SUB"] = 33] = "SUB";
  Opcode2[Opcode2["MUL"] = 34] = "MUL";
  Opcode2[Opcode2["DIV"] = 35] = "DIV";
  Opcode2[Opcode2["MOD"] = 36] = "MOD";
  Opcode2[Opcode2["AND"] = 40] = "AND";
  Opcode2[Opcode2["OR"] = 41] = "OR";
  Opcode2[Opcode2["XOR"] = 42] = "XOR";
  Opcode2[Opcode2["NOT"] = 43] = "NOT";
  Opcode2[Opcode2["SHL"] = 44] = "SHL";
  Opcode2[Opcode2["SHR"] = 45] = "SHR";
  Opcode2[Opcode2["SAR"] = 46] = "SAR";
  Opcode2[Opcode2["CMP"] = 48] = "CMP";
  Opcode2[Opcode2["CMPU"] = 49] = "CMPU";
  Opcode2[Opcode2["JMP"] = 64] = "JMP";
  Opcode2[Opcode2["JZ"] = 65] = "JZ";
  Opcode2[Opcode2["JNZ"] = 66] = "JNZ";
  Opcode2[Opcode2["JC"] = 67] = "JC";
  Opcode2[Opcode2["JV"] = 68] = "JV";
  Opcode2[Opcode2["CALL"] = 72] = "CALL";
  Opcode2[Opcode2["RET"] = 73] = "RET";
  Opcode2[Opcode2["HOST"] = 96] = "HOST";
  Opcode2[Opcode2["HALT"] = 104] = "HALT";
  Opcode2[Opcode2["TRAP"] = 105] = "TRAP";
  return Opcode2;
})(Opcode || {});
var INSTRUCTION_SIZES = {
  [0 /* LI */]: 4,
  // RI16
  [1 /* MOV */]: 2,
  // RR (packed)
  [2 /* LDB */]: 4,
  // MEM: opcode + reg + vaddr16
  [3 /* LDW */]: 4,
  [4 /* STB */]: 4,
  [5 /* STW */]: 4,
  [6 /* COPY */]: 8,
  // BLOCK: srcVaddr16 + dstVaddr16 + len16
  [7 /* MOVE */]: 8,
  [32 /* ADD */]: 2,
  [33 /* SUB */]: 2,
  [34 /* MUL */]: 2,
  [35 /* DIV */]: 2,
  [36 /* MOD */]: 2,
  [40 /* AND */]: 2,
  [41 /* OR */]: 2,
  [42 /* XOR */]: 2,
  [43 /* NOT */]: 2,
  [44 /* SHL */]: 2,
  [45 /* SHR */]: 2,
  [46 /* SAR */]: 2,
  [48 /* CMP */]: 2,
  [49 /* CMPU */]: 2,
  [64 /* JMP */]: 3,
  [65 /* JZ */]: 3,
  [66 /* JNZ */]: 3,
  [67 /* JC */]: 3,
  [68 /* JV */]: 3,
  [72 /* CALL */]: 3,
  [73 /* RET */]: 2,
  [96 /* HOST */]: 2,
  [104 /* HALT */]: 1,
  [105 /* TRAP */]: 2
};
function isGPR(reg) {
  return reg >= 0 /* R0 */ && reg <= 7 /* R7 */;
}
var REGISTER_NAMES = {
  "R0": 0,
  "R1": 1,
  "R2": 2,
  "R3": 3,
  "R4": 4,
  "R5": 5,
  "R6": 6,
  "R7": 7,
  "IP": 8,
  "SP": 9,
  "FP": 10,
  "FL": 11
};

// src/assembler.ts
var Assembler = class {
  constructor(options = {}) {
    this.diagnostics = [];
    this.labels = /* @__PURE__ */ new Map();
    this.instructions = [];
    this.symbols = /* @__PURE__ */ new Map();
    this.capabilities = /* @__PURE__ */ new Set();
    this.currentAddress = 0;
    this.currentLine = 0;
    this.currentFile = "<input>";
    this.currentSection = "code";
    this.constantPool = [];
    this.firmwareEnabled = true;
    this.options = options;
  }
  async assemble(source, fileName = "<input>") {
    this.reset();
    this.currentFile = fileName;
    this.currentAddress = this.options.origin || 0;
    this.firstPass(source);
    const firstPassErrors = this.diagnostics.filter((d) => d.severity === "error");
    if (firstPassErrors.length > 0) {
      return this.createResultSync(new Uint8Array(0));
    }
    const code = this.secondPass();
    const secondPassErrors = this.diagnostics.filter((d) => d.severity === "error");
    if (secondPassErrors.length > 0) {
      return this.createResultSync(new Uint8Array(0));
    }
    return this.createResult(new Uint8Array(code));
  }
  reset() {
    this.diagnostics = [];
    this.labels.clear();
    this.instructions = [];
    this.symbols.clear();
    this.capabilities.clear();
    this.constantPool = [];
    this.firmwareEnabled = this.options.firmware ?? true;
    this.currentAddress = this.options.origin || 0;
    this.currentLine = 0;
    this.currentSection = "code";
    this.constantPool = [];
  }
  firstPass(source) {
    const lines = source.split("\n");
    this.currentAddress = this.options.origin || 0;
    for (let i = 0; i < lines.length; i++) {
      this.currentLine = i + 1;
      const line = lines[i];
      const commentIndex = line.indexOf(";");
      const cleanLine = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
      const trimmed = cleanLine.trim();
      if (!trimmed) continue;
      if (trimmed.startsWith(".")) {
        this.handleDirective(trimmed);
        continue;
      }
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
  secondPass() {
    const code = [];
    if (this.firmwareEnabled) {
      const firmware = this.generateFirmware();
      code.push(...firmware);
      this.currentAddress = Math.max(this.currentAddress, 4096);
    }
    for (const instr of this.instructions) {
      while (code.length < instr.address) {
        code.push(0);
      }
      code.push(instr.opcode);
      this.emitOperands(instr, code);
    }
    const userCodeStart = this.firmwareEnabled ? 4096 : 0;
    const userCodeSize = code.length - userCodeStart;
    const MAX_USER_CODE_SIZE = 4096;
    if (userCodeSize > MAX_USER_CODE_SIZE) {
      this.addDiagnostic(
        "error",
        `User code too large: ${userCodeSize} bytes (max ${MAX_USER_CODE_SIZE} bytes / 1 page). Code must fit in a single 4KB page.`,
        0,
        0
      );
    }
    return code;
  }
  /**
   * Generates the firmware ROM (4KB at 0x0000-0x0FFF).
   * Contains: vector table, service constants, string/memory utils, host wrappers.
   */
  generateFirmware() {
    const fw = [];
    const vectors = [
      ["fw_version", 256],
      // Firmware version
      ["fw_strlen", 0],
      // Filled below
      ["fw_strcpy", 0],
      ["fw_strcmp", 0],
      ["fw_memcpy", 0],
      ["fw_memset", 0],
      ["fw_call_host", 0],
      // Generic HOST caller: R0=svc, R1=arg, R2=opt, R3=len
      ["fw_prop_read", 0],
      // R1=scope, R2=prop_id, R3=out_buf, R4=len
      ["fw_prop_write", 0],
      // R1=scope, R2=prop_id, R3=src_buf, R4=len
      ["fw_route_decide", 0],
      // R1=decision, R2=target_buf, R3=target_len
      ["fw_perm_check", 0],
      // R1=actor_buf, R2=perm_buf, R3=len
      ["fw_state_read", 0],
      // R1=key, R2=out_buf, R3=len
      ["fw_state_write", 0],
      // R1=key, R2=src_buf, R3=len
      ["fw_channel_resolve", 0]
      // R1=name_buf, R2=len, R3=out_buf, R4=len
    ];
    const vectorAddrs = /* @__PURE__ */ new Map();
    let vectorBase = fw.length;
    for (const [name] of vectors) {
      vectorAddrs.set(name, vectorBase);
      fw.push(0, 0);
      vectorBase += 2;
    }
    const constantsBase = fw.length;
    while (fw.length < 64) fw.push(0);
    this.emitConst(fw, "SERVICE_PROPERTY_READ", 256);
    this.emitConst(fw, "SERVICE_PROPERTY_WRITE", 257);
    this.emitConst(fw, "SERVICE_PROPERTY_EXISTS", 258);
    this.emitConst(fw, "SERVICE_PERM_CHECK", 512);
    this.emitConst(fw, "SERVICE_PERM_QUERY", 513);
    this.emitConst(fw, "SERVICE_ROUTE_DECISION", 768);
    this.emitConst(fw, "SERVICE_ROUTE_REDIRECT", 769);
    this.emitConst(fw, "SERVICE_ROUTE_RATE_LIMIT", 770);
    this.emitConst(fw, "SERVICE_TRANSFORM_APPLY", 1024);
    this.emitConst(fw, "SERVICE_STATE_READ", 1280);
    this.emitConst(fw, "SERVICE_STATE_WRITE", 1281);
    this.emitConst(fw, "SERVICE_CHANNEL_RESOLVE", 1536);
    this.emitConst(fw, "SERVICE_CHANNEL_POLICY", 1537);
    this.emitConst(fw, "SERVICE_MESSAGE_CLONE", 1792);
    this.emitConst(fw, "SERVICE_MESSAGE_ID", 1793);
    this.emitConst(fw, "SCOPE_MESSAGE", 1);
    this.emitConst(fw, "SCOPE_EMITTER", 2);
    this.emitConst(fw, "SCOPE_RECIPIENT", 3);
    this.emitConst(fw, "SCOPE_CHANNEL", 4);
    this.emitConst(fw, "SCOPE_SERVER", 5);
    this.emitConst(fw, "PROP_CONTENT", 16);
    this.emitConst(fw, "PROP_CHANNEL", 17);
    this.emitConst(fw, "PROP_DIRECTION", 18);
    this.emitConst(fw, "PROP_TYPE", 19);
    this.emitConst(fw, "PROP_LANG_SOURCE", 20);
    this.emitConst(fw, "PROP_LANG_TARGET", 21);
    this.emitConst(fw, "PROP_COLOR_MODE", 22);
    this.emitConst(fw, "PROP_FORMAT_PAPI", 23);
    this.emitConst(fw, "PROP_SLEEP_MILLIS", 24);
    this.emitConst(fw, "PROP_SOUNDS", 25);
    this.emitConst(fw, "PROP_CANCELLED", 26);
    this.emitConst(fw, "PROP_PROCESSED", 27);
    this.emitConst(fw, "PROP_TRANSLATE", 28);
    this.emitConst(fw, "PROP_ACTOR_NAME", 256);
    this.emitConst(fw, "PROP_ACTOR_UUID", 257);
    this.emitConst(fw, "PROP_ACTOR_LANG", 258);
    this.emitConst(fw, "PROP_ACTOR_WORLD", 259);
    this.emitConst(fw, "PROP_CHAN_NAME", 512);
    this.emitConst(fw, "PROP_CHAN_RATE", 513);
    this.emitConst(fw, "PROP_CHAN_SEND_POL", 514);
    this.emitConst(fw, "PROP_CHAN_RECV_POL", 515);
    this.emitConst(fw, "DECISION_LOG", 0);
    this.emitConst(fw, "DECISION_DROP", 1);
    this.emitConst(fw, "DECISION_REJECT", 2);
    this.emitConst(fw, "DECISION_REDIRECT", 3);
    this.emitConst(fw, "DECISION_CHAN_REDIR", 4);
    this.emitConst(fw, "DECISION_RATE_LIMIT", 5);
    this.emitConst(fw, "DECISION_CONTINUE", 6);
    while (fw.length < 256) fw.push(0);
    vectorAddrs.set("fw_version", 256);
    fw[256] = 1;
    fw[257] = 0;
    vectorAddrs.set("fw_strlen", fw.length);
    this.emitInstr(fw, 0 /* LI */, 0, 0);
    this.emitInstr(fw, 1 /* MOV */, 2, 1);
    this.emitInstr(fw, 2 /* LDB */, 3, 0);
    this.emitInstr(fw, 0 /* LI */, 4, 0);
    const strlen_loop = fw.length;
    this.emitInstr(fw, 2 /* LDB */, 3, 0);
    this.emitInstr(fw, 48 /* CMP */, 3, 0);
    this.emitInstr(fw, 65 /* JZ */, 0, 0);
    this.emitInstr(fw, 32 /* ADD */, 4, 0);
    this.emitInstr(fw, 32 /* ADD */, 2, 0);
    this.emitInstr(fw, 64 /* JMP */, 0, 0);
    const strlen_done = fw.length;
    this.emitInstr(fw, 1 /* MOV */, 0, 4);
    this.emitInstr(fw, 73 /* RET */, 0);
    while (fw.length < 4096) fw.push(0);
    for (const [name, addr] of vectorAddrs) {
      const idx = vectors.findIndex((v) => v[0] === name);
      if (idx >= 0) {
        const offset = 2 * idx;
        fw[offset] = addr & 255;
        fw[offset + 1] = addr >> 8 & 255;
      }
    }
    return fw;
  }
  emitConst(fw, name, value) {
    this.defineLabel(name, fw.length, 0);
    fw.push(value & 255, value >> 8 & 255);
  }
  emitInstr(fw, opcode, ...operands) {
    fw.push(opcode);
    for (const op of operands) {
      fw.push(op & 255);
    }
  }
  handleDirective(line) {
    const parts = line.split(/\s+/);
    const directive = parts[0].toLowerCase();
    switch (directive) {
      case ".section":
      case ".seg":
        this.currentSection = parts[1] || "code";
        break;
      case ".entry":
        this.options.entryPoint = parts[1];
        break;
      case ".origin":
      case ".org": {
        const newAddr = this.parseNumber(parts[1] || "0");
        if (newAddr < this.currentAddress) {
          this.addDiagnostic("error", `.origin cannot move backwards (current: 0x${this.currentAddress.toString(16)}, requested: 0x${newAddr.toString(16)})`, this.currentLine, 0);
        } else if (newAddr < (this.options.origin || 0)) {
          this.addDiagnostic("error", `.origin cannot be before program origin (0x${(this.options.origin || 0).toString(16)})`, this.currentLine, 0);
        } else {
          this.currentAddress = newAddr;
        }
        break;
      }
      case ".capability":
      case ".cap":
        if (parts[1]) {
          this.capabilities.add(parts[1]);
        }
        break;
      case ".firmware":
        if (parts[1]) {
          this.firmwareEnabled = parts[1].toLowerCase() !== "off" && parts[1] !== "0";
        }
        break;
      case ".align":
        const align = this.parseNumber(parts[1] || "2");
        if (!Number.isInteger(align) || align <= 0) {
          this.addDiagnostic("error", `.align requires positive integer, got: ${parts[1]}`, this.currentLine, 0);
        } else if ((align & align - 1) !== 0) {
          this.addDiagnostic("warning", `.align value should be power of two: ${align}`, this.currentLine, 0);
          while (this.currentAddress % align !== 0) {
            this.currentAddress++;
          }
        } else {
          while (this.currentAddress % align !== 0) {
            this.currentAddress++;
          }
        }
        break;
      case ".const":
        if (parts.length >= 3) {
          const name = parts[1];
          const value = this.parseNumber(parts[2]);
          this.defineLabel(name, value, this.currentLine);
          this.constantPool.push(value);
          this.symbols.set(name, this.constantPool.length - 1);
        }
        break;
      case ".equ":
      case ".set":
      case "=":
        if (parts.length >= 3) {
          const name = parts[1];
          const value = this.parseNumber(parts[2]);
          this.defineLabel(name, value, this.currentLine);
        }
        break;
      case ".end":
        break;
      default:
        this.addDiagnostic("warning", `Unknown directive: ${directive}`, this.currentLine, 0);
    }
  }
  defineLabel(name, address, line) {
    if (this.labels.has(name)) {
      this.addDiagnostic("error", `Duplicate label: ${name}`, line, 0);
    } else {
      this.labels.set(name, { name, address, line });
      this.symbols.set(name, address);
    }
  }
  parseInstruction(line) {
    const parts = this.tokenize(line);
    if (parts.length === 0) return;
    const mnemonic = parts[0].toUpperCase();
    const opcode = this.getOpcode(mnemonic);
    if (opcode === void 0) {
      this.addDiagnostic("error", `Unknown instruction: ${mnemonic}`, this.currentLine, 0);
      return;
    }
    const operands = this.parseOperands(parts.slice(1), opcode);
    this.validateOperands(opcode, operands, parts.slice(1).length);
    const size = INSTRUCTION_SIZES[opcode] || this.calculateSize(opcode, operands);
    const instruction = {
      opcode,
      operands,
      address: this.currentAddress,
      size,
      line: this.currentLine,
      source: line
    };
    this.instructions.push(instruction);
    this.currentAddress += size;
  }
  validateOperands(opcode, operands, providedCount) {
    const expected = this.getExpectedOperands(opcode);
    if (!expected) return;
    if (providedCount < expected.min) {
      this.addDiagnostic("error", `${Opcode[opcode]} requires at least ${expected.min} operand(s), got ${providedCount}`, this.currentLine, 0);
      return;
    }
    if (providedCount > expected.max) {
      this.addDiagnostic("error", `${Opcode[opcode]} accepts at most ${expected.max} operand(s), got ${providedCount}`, this.currentLine, 0);
      return;
    }
    for (let i = 0; i < operands.length; i++) {
      const op = operands[i];
      const exp = expected.types[i];
      if (!exp) continue;
      if (exp === "gpr" && op.type === "reg") {
        if (!isGPR(op.value)) {
          this.addDiagnostic("error", `Operand ${i + 1}: ${Opcode[opcode]} requires general-purpose register (R0-R7), got ${op.type}`, this.currentLine, 0);
        }
      } else if (exp === "any_reg" && op.type === "reg") {
      } else if (exp === "imm8" && (op.type === "imm8" || op.type === "imm16")) {
        const val = op.rawValue !== void 0 ? op.rawValue : op.value;
        if (val < -128 || val > 255) {
          this.addDiagnostic("error", `Operand ${i + 1}: immediate out of 8-bit range (-128..255): ${val}`, this.currentLine, 0);
        }
      } else if (exp === "imm16" && (op.type === "imm8" || op.type === "imm16")) {
        const val = op.rawValue !== void 0 ? op.rawValue : op.value;
        if (val < -32768 || val > 65535) {
          this.addDiagnostic("error", `Operand ${i + 1}: immediate out of 16-bit range: ${val}`, this.currentLine, 0);
        }
      } else if (exp === "vaddr" && (op.type === "imm16" || op.type === "label" || op.type === "vaddr")) {
      } else if (exp === "label" && op.type === "label") {
      } else if (exp === "reg" && op.type === "reg") {
      } else if (op.type !== exp) {
        this.addDiagnostic("error", `Operand ${i + 1}: ${Opcode[opcode]} expects ${exp}, got ${op.type}`, this.currentLine, 0);
      }
    }
  }
  getExpectedOperands(opcode) {
    switch (opcode) {
      case 0 /* LI */:
        return { min: 2, max: 2, types: ["gpr", "imm16"] };
      case 1 /* MOV */:
        return { min: 2, max: 2, types: ["gpr", "gpr"] };
      case 2 /* LDB */:
      case 3 /* LDW */:
      case 4 /* STB */:
      case 5 /* STW */:
        return { min: 2, max: 2, types: ["gpr", "vaddr"] };
      case 6 /* COPY */:
      case 7 /* MOVE */:
        return { min: 3, max: 3, types: ["vaddr", "vaddr", "imm16"] };
      case 32 /* ADD */:
      case 33 /* SUB */:
      case 34 /* MUL */:
      case 35 /* DIV */:
      case 36 /* MOD */:
      case 40 /* AND */:
      case 41 /* OR */:
      case 42 /* XOR */:
      case 44 /* SHL */:
      case 45 /* SHR */:
      case 46 /* SAR */:
      case 48 /* CMP */:
      case 49 /* CMPU */:
        return { min: 2, max: 2, types: ["gpr", "gpr"] };
      case 43 /* NOT */:
        return { min: 1, max: 1, types: ["gpr"] };
      case 64 /* JMP */:
      case 65 /* JZ */:
      case 66 /* JNZ */:
      case 67 /* JC */:
      case 68 /* JV */:
      case 72 /* CALL */:
        return { min: 1, max: 1, types: ["label"] };
      case 73 /* RET */:
        return { min: 1, max: 1, types: ["gpr"] };
      case 96 /* HOST */:
        return { min: 0, max: 0, types: [] };
      case 104 /* HALT */:
        return { min: 0, max: 0, types: [] };
      case 105 /* TRAP */:
        return { min: 1, max: 1, types: ["imm8"] };
      default:
        return null;
    }
  }
  tokenize(line) {
    const tokens = [];
    let current = "";
    let inQuotes = false;
    let quoteChar = "";
    for (let i = 0; i < line.length; i++) {
      const char = line[i];
      if ((char === '"' || char === "'") && !inQuotes) {
        inQuotes = true;
        quoteChar = char;
        current += char;
      } else if (char === quoteChar && inQuotes) {
        inQuotes = false;
        current += char;
      } else if ((char === "," || /\s/.test(char)) && !inQuotes) {
        if (current) {
          tokens.push(current);
          current = "";
        }
      } else if ((char === "+" || char === "-") && !inQuotes) {
        if (current && /[a-zA-Z0-9_]$/.test(current)) {
          current += char;
        } else {
          if (current) {
            tokens.push(current);
            current = "";
          }
          current += char;
        }
      } else {
        current += char;
      }
    }
    if (current) tokens.push(current);
    const merged = [];
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i];
      if (i > 0 && (token === "+" || token === "-") && /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(tokens[i - 1]) && i + 1 < tokens.length && this.isNumber(tokens[i + 1])) {
        merged[merged.length - 1] = tokens[i - 1] + token + tokens[i + 1];
        i++;
      } else {
        merged.push(token);
      }
    }
    return merged.map((t) => t.replace(/^,|,$/g, "").trim()).filter((t) => t);
  }
  getOpcode(mnemonic) {
    return Opcode[mnemonic];
  }
  parseOperands(tokens, opcode) {
    const operands = [];
    for (const token of tokens) {
      if (REGISTER_NAMES[token.toUpperCase()] !== void 0) {
        operands.push({ type: "reg", value: REGISTER_NAMES[token.toUpperCase()] });
        continue;
      }
      if (token.includes(",")) {
        const [rd, rs] = token.split(",").map((s) => s.trim().toUpperCase());
        if (REGISTER_NAMES[rd] !== void 0 && REGISTER_NAMES[rs] !== void 0) {
          operands.push({ type: "packed_rr", rd: REGISTER_NAMES[rd], rs: REGISTER_NAMES[rs] });
          continue;
        }
      }
      if (/^[a-zA-Z_][a-zA-Z0-9_]*([+-]\d+)?$/.test(token) && !this.isNumber(token)) {
        operands.push({ type: "label", name: token });
        continue;
      }
      const value = this.parseNumber(token);
      if (value >= -128 && value <= 255) {
        operands.push({ type: "imm8", value: value & 255, rawValue: value });
      } else {
        operands.push({ type: "imm16", value: value & 65535, rawValue: value });
      }
    }
    return operands;
  }
  isNumber(token) {
    return /^0x[0-9a-fA-F]+$/.test(token) || /^\d+$/.test(token) || /^-?\d+$/.test(token);
  }
  parseNumber(token) {
    if (token.startsWith("0x") || token.startsWith("0X")) {
      return parseInt(token.substring(2), 16);
    }
    return parseInt(token, 10);
  }
  calculateSize(opcode, operands) {
    return INSTRUCTION_SIZES[opcode] || 1;
  }
  emitOperands(instr, code) {
    const { opcode, operands } = instr;
    switch (opcode) {
      case 0 /* LI */: {
        const rd = this.getRegOperand(operands, 0);
        const imm = this.getImm16Operand(operands, 1);
        code.push(rd);
        code.push(imm >> 8 & 255);
        code.push(imm & 255);
        break;
      }
      case 1 /* MOV */: {
        const packed = this.getPackedRROperand(operands);
        if (packed) {
          code.push(packed.rd << 4 | packed.rs);
        } else {
          const rd = this.getRegOperand(operands, 0);
          const rs = this.getRegOperand(operands, 1);
          code.push(rd << 4 | rs);
        }
        break;
      }
      case 2 /* LDB */:
      case 3 /* LDW */:
      case 4 /* STB */:
      case 5 /* STW */: {
        const reg = this.getRegOperand(operands, 0);
        const vaddr = this.getVaddrOperand(operands, 1);
        code.push(reg);
        code.push(vaddr >> 8 & 255);
        code.push(vaddr & 255);
        break;
      }
      case 6 /* COPY */:
      case 7 /* MOVE */: {
        const src = this.getVaddrOperand(operands, 0);
        const dst = this.getVaddrOperand(operands, 1);
        const len = this.getImm16Operand(operands, 2);
        code.push(src >> 8 & 255, src & 255);
        code.push(dst >> 8 & 255, dst & 255);
        code.push(len >> 8 & 255, len & 255);
        code.push(0);
        break;
      }
      case 32 /* ADD */:
      case 33 /* SUB */:
      case 34 /* MUL */:
      case 35 /* DIV */:
      case 36 /* MOD */:
      case 40 /* AND */:
      case 41 /* OR */:
      case 42 /* XOR */:
      case 44 /* SHL */:
      case 45 /* SHR */:
      case 46 /* SAR */:
      case 48 /* CMP */:
      case 49 /* CMPU */: {
        const packed = this.getPackedRROperand(operands);
        if (packed) {
          code.push(packed.rd << 4 | packed.rs);
        } else {
          const rd = this.getRegOperand(operands, 0);
          const rs = this.getRegOperand(operands, 1);
          code.push(rd << 4 | rs);
        }
        break;
      }
      case 43 /* NOT */: {
        const rd = this.getRegOperand(operands, 0);
        code.push(rd & 15);
        break;
      }
      case 64 /* JMP */:
      case 65 /* JZ */:
      case 66 /* JNZ */:
      case 67 /* JC */:
      case 68 /* JV */:
      case 72 /* CALL */: {
        const offset = this.getBranchOffset(operands, 0, instr.address, opcode);
        code.push(offset >> 8 & 255);
        code.push(offset & 255);
        break;
      }
      case 73 /* RET */: {
        const rs = this.getRegOperand(operands, 0);
        code.push((rs & 15) << 4);
        break;
      }
      case 96 /* HOST */: {
        code.push(0);
        break;
      }
      case 104 /* HALT */: {
        break;
      }
      case 105 /* TRAP */: {
        const code8 = this.getImm8Operand(operands, 0);
        code.push(code8);
        break;
      }
    }
  }
  getRegOperand(operands, index) {
    const op = operands[index];
    if (op && op.type === "reg") return op.value;
    return 0;
  }
  getImm8Operand(operands, index) {
    const op = operands[index];
    if (op && (op.type === "imm8" || op.type === "imm16")) return op.value & 255;
    return 0;
  }
  getImm16Operand(operands, index) {
    const op = operands[index];
    if (op && (op.type === "imm16" || op.type === "imm8")) return op.value & 65535;
    if (op && op.type === "label") {
      const match = op.name.match(/^([a-zA-Z_][a-zA-Z0-9_]*)([+-]\d+)?$/);
      if (match) {
        const labelName = match[1];
        const offsetStr = match[2] || "0";
        const offset = parseInt(offsetStr, 10);
        const label2 = this.labels.get(labelName);
        if (label2) return label2.address + offset & 65535;
        this.addDiagnostic("error", `Undefined label: ${labelName}`, 0, 0);
        return 0;
      }
      const label = this.labels.get(op.name);
      if (label) return label.address & 65535;
      this.addDiagnostic("error", `Undefined label: ${op.name}`, 0, 0);
      return 0;
    }
    return 0;
  }
  getVaddrOperand(operands, index) {
    return this.getImm16Operand(operands, index);
  }
  getPackedRROperand(operands) {
    for (const op of operands) {
      if (op.type === "packed_rr") return { rd: op.rd, rs: op.rs };
    }
    return null;
  }
  getBranchOffset(operands, index, currentAddr, opcode) {
    const op = operands[index];
    if (!op) return 0;
    if (op.type === "label") {
      const label = this.labels.get(op.name);
      if (label) {
        const nextAddr = currentAddr + (INSTRUCTION_SIZES[opcode] || 3);
        return label.address - nextAddr & 65535;
      }
      this.addDiagnostic("error", `Undefined label: ${op.name}`, 0, 0);
      return 0;
    }
    if (op.type === "imm16" || op.type === "imm8") {
      return op.value - (currentAddr + 3) & 65535;
    }
    return 0;
  }
  createResultSync(program) {
    const programImage = {
      formatVersion: 1,
      isaVersion: 1,
      minAbiVersion: 1,
      minFirmwareVersion: 1,
      entryPoint: this.getEntryPointAddress(),
      code: program,
      constants: this.constantPool,
      capabilities: Array.from(this.capabilities),
      hash: new Uint8Array(32),
      debugInfo: this.options.debug ? this.generateDebugInfo() : void 0
    };
    const symbolArray = [];
    for (const [name, address] of this.symbols) {
      symbolArray.push({ name, address, type: "code" });
    }
    return {
      program: programImage,
      symbols: symbolArray,
      diagnostics: this.diagnostics
    };
  }
  async createResult(program) {
    const programImageNoHash = {
      formatVersion: 1,
      isaVersion: 1,
      minAbiVersion: 1,
      minFirmwareVersion: 1,
      entryPoint: this.getEntryPointAddress(),
      code: program,
      constants: this.constantPool,
      capabilities: Array.from(this.capabilities),
      hash: new Uint8Array(32),
      // Placeholder
      debugInfo: this.options.debug ? this.generateDebugInfo() : void 0
    };
    const serializedNoHash = this.serializeProgramImageNoHash(programImageNoHash);
    const hash = await this.computeSHA256(serializedNoHash);
    const programImage = {
      ...programImageNoHash,
      hash
    };
    const symbolArray = [];
    for (const [name, address] of this.symbols) {
      symbolArray.push({ name, address, type: "code" });
    }
    return {
      program: programImage,
      symbols: symbolArray,
      diagnostics: this.diagnostics
    };
  }
  serializeProgramImageNoHash(image) {
    const parts = [];
    parts.push(image.formatVersion & 255);
    parts.push(image.isaVersion & 255);
    parts.push(image.minAbiVersion & 255);
    parts.push(image.minFirmwareVersion & 255);
    parts.push(image.entryPoint >> 8 & 255);
    parts.push(image.entryPoint & 255);
    parts.push(image.code.length >> 8 & 255);
    parts.push(image.code.length & 255);
    parts.push(image.constants.length >> 8 & 255);
    parts.push(image.constants.length & 255);
    parts.push(image.capabilities.length & 255);
    parts.push(0, 0, 0, 0, 0);
    parts.push(...Array.from(image.code));
    for (const c of image.constants) {
      parts.push(c >> 8 & 255, c & 255);
    }
    for (const cap of image.capabilities) {
      for (const ch of cap) {
        parts.push(ch.charCodeAt(0));
      }
      parts.push(0);
    }
    return new Uint8Array(parts);
  }
  async computeSHA256(data) {
    if (typeof crypto !== "undefined" && crypto.subtle) {
      const buffer = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
      const hashBuffer = await crypto.subtle.digest("SHA-256", buffer);
      return new Uint8Array(hashBuffer);
    } else if (typeof process !== "undefined" && process.versions?.node) {
      const { createHash } = await import("crypto");
      return new Uint8Array(createHash("sha256").update(Buffer.from(data)).digest());
    } else {
      throw new Error("SHA-256 not available: no Web Crypto API or Node.js crypto");
    }
  }
  getEntryPointAddress() {
    if (this.options.entryPoint) {
      const label = this.labels.get(this.options.entryPoint);
      if (label) return label.address;
      this.addDiagnostic("error", `Entry point label not found: ${this.options.entryPoint}`, 0, 0);
    }
    return this.options.origin || 0;
  }
  generateDebugInfo() {
    let info = "";
    for (const instr of this.instructions) {
      info += `${instr.address.toString(16).padStart(4, "0")}: ${instr.source}
`;
    }
    for (const [name, address] of this.symbols) {
      info += `SYM ${name} = 0x${address.toString(16).padStart(4, "0")}
`;
    }
    return info;
  }
  serializeProgramImage(image) {
    const parts = [];
    parts.push(image.formatVersion & 255);
    parts.push(image.isaVersion & 255);
    parts.push(image.minAbiVersion & 255);
    parts.push(image.minFirmwareVersion & 255);
    parts.push(image.entryPoint >> 8 & 255);
    parts.push(image.entryPoint & 255);
    parts.push(image.code.length >> 8 & 255);
    parts.push(image.code.length & 255);
    parts.push(image.constants.length >> 8 & 255);
    parts.push(image.constants.length & 255);
    parts.push(image.capabilities.length & 255);
    parts.push(0, 0, 0, 0, 0);
    parts.push(...Array.from(image.code));
    for (const c of image.constants) {
      parts.push(c >> 8 & 255, c & 255);
    }
    for (const cap of image.capabilities) {
      for (const ch of cap) {
        parts.push(ch.charCodeAt(0));
      }
      parts.push(0);
    }
    parts.push(...Array.from(image.hash));
    return new Uint8Array(parts);
  }
  addDiagnostic(severity, message, line, column) {
    this.diagnostics.push({
      severity,
      message,
      line: line || this.currentLine,
      column,
      file: this.currentFile
    });
  }
};
export {
  Assembler
};
