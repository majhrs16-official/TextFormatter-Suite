"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Assembler = void 0;
/**
 * iFlow VM Assembler - Two-pass assembler for .flowasm to .flow binary
 */
const types_1 = require("./types");
class Assembler {
    constructor(options = {}) {
        this.diagnostics = [];
        this.labels = new Map();
        this.instructions = [];
        this.symbols = new Map();
        this.capabilities = new Set();
        this.currentAddress = 0;
        this.currentLine = 0;
        this.currentFile = '<input>';
        this.currentSection = 'code';
        this.constantPool = [];
        this.options = options;
    }
    async assemble(source, fileName = '<input>') {
        this.reset();
        this.currentFile = fileName;
        this.currentAddress = this.options.origin || 0;
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
    reset() {
        this.diagnostics = [];
        this.labels.clear();
        this.instructions = [];
        this.symbols.clear();
        this.capabilities.clear();
        this.currentAddress = this.options.origin || 0;
        this.currentLine = 0;
        this.currentSection = 'code';
        this.constantPool = [];
    }
    firstPass(source) {
        const lines = source.split('\n');
        this.currentAddress = this.options.origin || 0;
        for (let i = 0; i < lines.length; i++) {
            this.currentLine = i + 1;
            const line = lines[i];
            // Remove comments and trim
            const commentIndex = line.indexOf(';');
            const cleanLine = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
            const trimmed = cleanLine.trim();
            if (!trimmed)
                continue;
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
    secondPass() {
        const code = [];
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
        return code;
    }
    handleDirective(line) {
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
            case '.org':
                this.currentAddress = this.parseNumber(parts[1] || '0');
                break;
            case '.capability':
            case '.cap':
                if (parts[1]) {
                    this.capabilities.add(parts[1]);
                }
                break;
            case '.align':
                const align = this.parseNumber(parts[1] || '2');
                while (this.currentAddress % align !== 0) {
                    this.currentAddress++;
                }
                break;
            case '.const':
                if (parts.length >= 3) {
                    const name = parts[1];
                    const value = this.parseNumber(parts[2]);
                    // Define as a label/equate for use in instructions
                    this.defineLabel(name, value, this.currentLine);
                    // Also add to constant pool
                    this.constantPool.push(value);
                    this.symbols.set(name, this.constantPool.length - 1);
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
    defineLabel(name, address, line) {
        if (this.labels.has(name)) {
            this.addDiagnostic('error', `Duplicate label: ${name}`, line, 0);
        }
        else {
            this.labels.set(name, { name, address, line });
            this.symbols.set(name, address);
        }
    }
    parseInstruction(line) {
        const parts = this.tokenize(line);
        if (parts.length === 0)
            return;
        const mnemonic = parts[0].toUpperCase();
        const opcode = this.getOpcode(mnemonic);
        if (opcode === undefined) {
            this.addDiagnostic('error', `Unknown instruction: ${mnemonic}`, this.currentLine, 0);
            return;
        }
        const operands = this.parseOperands(parts.slice(1), opcode);
        const size = types_1.INSTRUCTION_SIZES[opcode] || this.calculateSize(opcode, operands);
        const instruction = {
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
    tokenize(line) {
        // Split by comma or whitespace, keeping quoted strings together
        // Also handle simple expressions like LABEL + OFFSET
        const tokens = [];
        let current = '';
        let inQuotes = false;
        let quoteChar = '';
        for (let i = 0; i < line.length; i++) {
            const char = line[i];
            if ((char === '"' || char === "'") && !inQuotes) {
                inQuotes = true;
                quoteChar = char;
                current += char;
            }
            else if (char === quoteChar && inQuotes) {
                inQuotes = false;
                current += char;
            }
            else if ((char === ',' || /\s/.test(char)) && !inQuotes) {
                if (current) {
                    tokens.push(current);
                    current = '';
                }
            }
            else if ((char === '+' || char === '-') && !inQuotes) {
                // Handle +/- as part of expression (e.g., LABEL+OFFSET)
                if (current && /[a-zA-Z0-9_]$/.test(current)) {
                    current += char;
                }
                else {
                    if (current) {
                        tokens.push(current);
                        current = '';
                    }
                    current += char;
                }
            }
            else {
                current += char;
            }
        }
        if (current)
            tokens.push(current);
        // Post-process: merge tokens like "LABEL+OFFSET" into single token
        const merged = [];
        for (let i = 0; i < tokens.length; i++) {
            const token = tokens[i];
            if (i > 0 && (token === '+' || token === '-') &&
                /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(tokens[i - 1]) &&
                i + 1 < tokens.length && this.isNumber(tokens[i + 1])) {
                // Merge: previous_label + number
                merged[merged.length - 1] = tokens[i - 1] + token + tokens[i + 1];
                i++; // Skip the number token
            }
            else {
                merged.push(token);
            }
        }
        // Debug
        // console.error(`TOKENS: ${JSON.stringify(merged)}`);
        return merged.map(t => t.replace(/^,|,$/g, '').trim()).filter(t => t);
    }
    getOpcode(mnemonic) {
        return types_1.Opcode[mnemonic];
    }
    parseOperands(tokens, opcode) {
        const operands = [];
        for (const token of tokens) {
            // Register
            if (types_1.REGISTER_NAMES[token.toUpperCase()] !== undefined) {
                operands.push({ type: 'reg', value: types_1.REGISTER_NAMES[token.toUpperCase()] });
                continue;
            }
            // Packed RR format (e.g., "R1,R2")
            if (token.includes(',')) {
                const [rd, rs] = token.split(',').map(s => s.trim().toUpperCase());
                if (types_1.REGISTER_NAMES[rd] !== undefined && types_1.REGISTER_NAMES[rs] !== undefined) {
                    operands.push({ type: 'packed_rr', rd: types_1.REGISTER_NAMES[rd], rs: types_1.REGISTER_NAMES[rs] });
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
                operands.push({ type: 'imm8', value: value & 0xFF });
            }
            else {
                operands.push({ type: 'imm16', value: value & 0xFFFF });
            }
        }
        return operands;
    }
    isNumber(token) {
        return /^0x[0-9a-fA-F]+$/.test(token) || /^\d+$/.test(token) || /^-?\d+$/.test(token);
    }
    parseNumber(token) {
        if (token.startsWith('0x') || token.startsWith('0X')) {
            return parseInt(token.substring(2), 16);
        }
        return parseInt(token, 10);
    }
    calculateSize(opcode, operands) {
        return types_1.INSTRUCTION_SIZES[opcode] || 1;
    }
    emitOperands(instr, code) {
        const { opcode, operands } = instr;
        switch (opcode) {
            case types_1.Opcode.LI: { // LI rd, imm16
                const rd = this.getRegOperand(operands, 0);
                const imm = this.getImm16Operand(operands, 1);
                code.push(rd);
                code.push((imm >> 8) & 0xFF);
                code.push(imm & 0xFF);
                break;
            }
            case types_1.Opcode.MOV: { // MOV rd, rs (packed)
                const packed = this.getPackedRROperand(operands);
                if (packed) {
                    code.push((packed.rd << 4) | packed.rs);
                }
                else {
                    const rd = this.getRegOperand(operands, 0);
                    const rs = this.getRegOperand(operands, 1);
                    code.push((rd << 4) | rs);
                }
                break;
            }
            case types_1.Opcode.LDB:
            case types_1.Opcode.LDW:
            case types_1.Opcode.STB:
            case types_1.Opcode.STW: { // MEM rd/rs, vaddr16
                const reg = this.getRegOperand(operands, 0);
                const vaddr = this.getVaddrOperand(operands, 1);
                code.push(reg);
                code.push((vaddr >> 8) & 0xFF);
                code.push(vaddr & 0xFF);
                break;
            }
            case types_1.Opcode.COPY:
            case types_1.Opcode.MOVE: { // BLOCK srcVaddr16, dstVaddr16, len16 (8 bytes: opcode + src16 + dst16 + len16 + padding)
                const src = this.getVaddrOperand(operands, 0);
                const dst = this.getVaddrOperand(operands, 1);
                const len = this.getImm16Operand(operands, 2);
                code.push((src >> 8) & 0xFF, src & 0xFF);
                code.push((dst >> 8) & 0xFF, dst & 0xFF);
                code.push((len >> 8) & 0xFF, len & 0xFF);
                code.push(0x00); // Padding byte to make 8 bytes total (1 opcode + 7 operands = 8)
                break;
            }
            case types_1.Opcode.ADD:
            case types_1.Opcode.SUB:
            case types_1.Opcode.MUL:
            case types_1.Opcode.DIV:
            case types_1.Opcode.MOD:
            case types_1.Opcode.AND:
            case types_1.Opcode.OR:
            case types_1.Opcode.XOR:
            case types_1.Opcode.SHL:
            case types_1.Opcode.SHR:
            case types_1.Opcode.SAR:
            case types_1.Opcode.CMP:
            case types_1.Opcode.CMPU: { // RR rd, rs (packed)
                const packed = this.getPackedRROperand(operands);
                if (packed) {
                    code.push((packed.rd << 4) | packed.rs);
                }
                else {
                    const rd = this.getRegOperand(operands, 0);
                    const rs = this.getRegOperand(operands, 1);
                    code.push((rd << 4) | rs);
                }
                break;
            }
            case types_1.Opcode.NOT: { // R rd (2 bytes: opcode + reg)
                const rd = this.getRegOperand(operands, 0);
                code.push(rd & 0x0F);
                break;
            }
            case types_1.Opcode.JMP:
            case types_1.Opcode.JZ:
            case types_1.Opcode.JNZ:
            case types_1.Opcode.JC:
            case types_1.Opcode.JV:
            case types_1.Opcode.CALL: { // BR16 offset16
                const offset = this.getBranchOffset(operands, 0, instr.address, opcode);
                code.push((offset >> 8) & 0xFF);
                code.push(offset & 0xFF);
                break;
            }
            case types_1.Opcode.RET: { // R rs (2 bytes: opcode + rs<<4)
                const rs = this.getRegOperand(operands, 0);
                code.push((rs & 0x0F) << 4);
                break;
            }
            case types_1.Opcode.HOST: { // HOST (2 bytes: opcode + dummy)
                // Service ID is passed in R0, not in instruction
                // Emit dummy byte to match instructionSize(2)
                code.push(0);
                break;
            }
            case types_1.Opcode.HALT: {
                // No operands
                break;
            }
            case types_1.Opcode.TRAP: { // TRAP code8
                const code8 = this.getImm8Operand(operands, 0);
                code.push(code8);
                break;
            }
        }
    }
    getRegOperand(operands, index) {
        const op = operands[index];
        if (op && op.type === 'reg')
            return op.value;
        return 0;
    }
    getImm8Operand(operands, index) {
        const op = operands[index];
        if (op && (op.type === 'imm8' || op.type === 'imm16'))
            return op.value & 0xFF;
        return 0;
    }
    getImm16Operand(operands, index) {
        const op = operands[index];
        if (op && (op.type === 'imm16' || op.type === 'imm8'))
            return op.value & 0xFFFF;
        if (op && op.type === 'label') {
            // Handle LABEL+OFFSET or LABEL-OFFSET format
            const match = op.name.match(/^([a-zA-Z_][a-zA-Z0-9_]*)([+-]\d+)?$/);
            if (match) {
                const labelName = match[1];
                const offsetStr = match[2] || '0';
                const offset = parseInt(offsetStr, 10);
                const label = this.labels.get(labelName);
                if (label)
                    return (label.address + offset) & 0xFFFF;
                this.addDiagnostic('error', `Undefined label: ${labelName}`, 0, 0);
                return 0;
            }
            // Fallback for plain labels
            const label = this.labels.get(op.name);
            if (label)
                return label.address & 0xFFFF;
            this.addDiagnostic('error', `Undefined label: ${op.name}`, 0, 0);
            return 0;
        }
        return 0;
    }
    getVaddrOperand(operands, index) {
        return this.getImm16Operand(operands, index);
    }
    getPackedRROperand(operands) {
        for (const op of operands) {
            if (op.type === 'packed_rr')
                return { rd: op.rd, rs: op.rs };
        }
        return null;
    }
    getBranchOffset(operands, index, currentAddr, opcode) {
        const op = operands[index];
        if (!op)
            return 0;
        if (op.type === 'label') {
            const label = this.labels.get(op.name);
            if (label) {
                // Calculate relative offset from next instruction
                const nextAddr = currentAddr + (types_1.INSTRUCTION_SIZES[opcode] || 3);
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
    createResultSync(program) {
        // For error cases, just return without computing hash
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
            debugInfo: this.options.debug ? this.generateDebugInfo() : undefined,
        };
        const symbolArray = [];
        for (const [name, address] of this.symbols) {
            symbolArray.push({ name, address, type: 'code' });
        }
        return {
            program: programImage,
            symbols: symbolArray,
            diagnostics: this.diagnostics,
        };
    }
    async createResult(program) {
        // Serialize program image without hash first
        const programImageNoHash = {
            formatVersion: 1,
            isaVersion: 1,
            minAbiVersion: 1,
            minFirmwareVersion: 1,
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
        const programImage = {
            ...programImageNoHash,
            hash,
        };
        const symbolArray = [];
        for (const [name, address] of this.symbols) {
            symbolArray.push({ name, address, type: 'code' });
        }
        return {
            program: programImage,
            symbols: symbolArray,
            diagnostics: this.diagnostics,
        };
    }
    serializeProgramImageNoHash(image) {
        const parts = [];
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
    async computeSHA256(data) {
        // Use Web Crypto API (available in Node.js 15+ and browsers)
        const crypto = await import('crypto');
        const hash = crypto.createHash('sha256');
        hash.update(Buffer.from(data));
        return new Uint8Array(hash.digest());
    }
    getEntryPointAddress() {
        if (this.options.entryPoint) {
            const label = this.labels.get(this.options.entryPoint);
            if (label)
                return label.address;
        }
        return this.options.origin || 0;
    }
    generateDebugInfo() {
        let info = '';
        for (const instr of this.instructions) {
            info += `${instr.address.toString(16).padStart(4, '0')}: ${instr.source}\n`;
        }
        for (const [name, address] of this.symbols) {
            info += `SYM ${name} = 0x${address.toString(16).padStart(4, '0')}\n`;
        }
        return info;
    }
    serializeProgramImage(image) {
        // Simple serialization: header + code + constants + capabilities
        const parts = [];
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
    addDiagnostic(severity, message, line, column) {
        this.diagnostics.push({
            severity,
            message,
            line: line || this.currentLine,
            column,
            file: this.currentFile,
        });
    }
}
exports.Assembler = Assembler;
//# sourceMappingURL=assembler.js.map