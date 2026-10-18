package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.logger.DebugConfig;
import me.majhrs16.suite.logger.LoggerUtils;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import me.majhrs16.suite.iflow.vm.service.ServiceResult;

import java.util.Objects;
import java.util.function.IntBinaryOperator;

/**
 * VM Interpreter: fetch / decode / execute loop.
 * <p>
 * Executes a {@link ProgramImage} within an {@link ExecutionContext}.
 * Implements the ISA v2 instruction set.
 * </p>
 * <p>
 * The CPU is a bus master. Both data transactions (load/store, CALL/RET stack
 * traffic) and <em>instruction fetch</em> travel on the execution's data bus over
 * the flat 64KB SoftMMU: the interpreter never reads the deployment image, it
 * fetches code through the MMU so the page {@code EXECUTE} permission is
 * enforced (IFLOW-AUD-023). Fetched bytes are served from a validated page
 * window kept in the {@link ExecutionContext} (an I-cache line per page), so the
 * interpreter itself holds no mutable state and can be shared per program
 * (IFLOW-AUD-029).
 * </p>
 */
public final class VmInterpreter {

    private static final int MAX_INSTRUCTION_COST = 1000; // Safety limit

    private final ProgramImage program;
    private final HostServiceRegistry serviceRegistry;

    /**
     * Creates an interpreter for a program. The interpreter is immutable and
     * stateless: it reads the execution's bus/MMU from the {@link ExecutionContext}
     * passed to {@link #execute(ExecutionContext)}, so one instance can be reused
     * for every execution of the same program (and across threads).
     */
    public VmInterpreter(ProgramImage program, HostServiceRegistry serviceRegistry) {
        this.program = Objects.requireNonNull(program);
        this.serviceRegistry = Objects.requireNonNull(serviceRegistry);
    }

    /**
     * Executes the program from its entry point.
     * @param context the execution context (must be initialized)
     * @throws VmException on execution error
     */
    public void execute(ExecutionContext context) {
        int codeSize = program.codeSize();

        // Set entry point
        context.registers().set(RegisterFile.IP, program.entryPoint());

        // Verify entry point is valid
        if (program.entryPoint() < 0 || program.entryPoint() >= codeSize) {
            throw VmException.invalidJump(program.entryPoint(), program.entryPoint());
        }

        // Main interpreter loop
        while (true) {
            // Check cancellation
            context.checkCancelled();

            // Fetch (through the bus/MMU: enforces the code page's EXECUTE permission)
            int ip = context.registers().get(RegisterFile.IP);
            if (ip < 0 || ip >= codeSize) {
                throw VmException.invalidJump(ip, ip);
            }

            // Check minimum fuel for next instruction
            context.consumeFuel(1); // Base cost per instruction

            // Decode
            int opcode = fetch(context, ip);
            if (!Opcode.isValid(opcode)) {
                throw VmException.invalidOpcode(opcode, ip);
            }

            int instrSize = Opcode.instructionSize(opcode);
            if (ip + instrSize > codeSize) {
                throw VmException.invalidOperand("Truncated instruction at IP=0x" + Integer.toHexString(ip), ip);
            }

            // Debug: log the full VM cycle state before execution. Lazy on purpose:
            // the register dump is only built if the DEBUG guard *inside*
            // LoggerUtils.Module.debug passes, so nothing is computed when debug
            // is compiled out — no external `if (DebugConfig.DEBUG)` needed here.
            LoggerUtils.Module.debug("iflow", () -> String.format(
                "VM_CYCLE IP=0x%04X OP=0x%02X(%s) REGS=%s FUEL=%d",
                ip, opcode, Opcode.name(opcode), formatRegisters(context.registers()), context.fuelRemaining()));

            // Execute
            boolean ipModifiedByInstruction;
            try {
                ipModifiedByInstruction = executeInstruction(context, opcode, ip);
            } catch (VmException e) {
                throw e;
            } catch (Exception e) {
                throw new VmException(VmException.Code.HOST_ERROR, "Execution error: " + e.getMessage(), ip, e);
            }

            // Debug: sparse memory trace. Only memory/block instructions emit an
            // extra line, recording just the touched address(es) and value(s) —
            // never a full memory dump. Unlike the VM-cycle line, this one keeps an
            // external DEBUG guard: it is NOT redundant with the guard inside
            // LoggerUtils.Module.debug, because it also skips the per-cycle dispatch
            // and the O(len) block checksums when debug is off.
            if (DebugConfig.DEBUG) {
                logMemoryTrace(context, opcode, ip);
            }

            // Advance IP (unless instruction modified it)
            int newIp = context.registers().get(RegisterFile.IP);
            if (!ipModifiedByInstruction && newIp == ip) {
                newIp = ip + instrSize;
                context.registers().set(RegisterFile.IP, newIp);
            }

            // Reached the end of the code (explicit HALT sets IP=codeSize, or the
            // last instruction simply runs off the end): stop normally.
            if (newIp >= codeSize) {
                break;
            }
        }
    }

    /** Fetches an instruction byte through the execution's bus/MMU (validated window). */
    private static int fetch(ExecutionContext ctx, int addr) {
        return ctx.fetchCodeByte(addr);
    }

    private boolean executeInstruction(ExecutionContext ctx, int opcode, int ip) {
        RegisterFile regs = ctx.registers();

        switch (opcode) {
            // ===== Data transfer =====
            case Opcode.LI -> {
                // RI16: LI Rd, imm16
                int rd = fetch(ctx, ip + 1);
                int imm = (fetch(ctx, ip + 2) << 8) | fetch(ctx, ip + 3);
                checkGPR(rd, ip);
                regs.set(rd, imm);
                regs.updateZN(imm);
                return false;
            }
            case Opcode.MOV -> {
                // RR: MOV Rd, Rs (packed in one byte: high nibble Rd, low nibble Rs)
                int packed = fetch(ctx, ip + 1);
                int rd = (packed >> 4) & 0x0F;
                int rs = packed & 0x0F;
                checkGPRs(ip, rd, rs);
                int val = regs.get(rs);
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }

            // ===== Memory =====
            case Opcode.LDB -> {
                // MEM: LDB Rd, [virtualAddr]
                int rd = fetch(ctx, ip + 1);
                int virtualAddr = (fetch(ctx, ip + 2) << 8) | fetch(ctx, ip + 3);
                checkGPR(rd, ip);
                int val = ctx.bus().readByte(virtualAddr);
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }
            case Opcode.LDW -> {
                int rd = fetch(ctx, ip + 1);
                int virtualAddr = (fetch(ctx, ip + 2) << 8) | fetch(ctx, ip + 3);
                checkGPR(rd, ip);
                int val = ctx.bus().readWord(virtualAddr);
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }
            case Opcode.STB -> {
                int rs = fetch(ctx, ip + 1);
                int virtualAddr = (fetch(ctx, ip + 2) << 8) | fetch(ctx, ip + 3);
                checkGPR(rs, ip);
                ctx.bus().writeByte(virtualAddr, regs.get(rs));
                return false;
            }
            case Opcode.STW -> {
                int rs = fetch(ctx, ip + 1);
                int virtualAddr = (fetch(ctx, ip + 2) << 8) | fetch(ctx, ip + 3);
                checkGPR(rs, ip);
                ctx.bus().writeWord(virtualAddr, regs.get(rs));
                return false;
            }

            // ===== Indirect memory (ISA v2): address is the value of a register =====
            case Opcode.LDBI -> {
                int packed = fetch(ctx, ip + 1);
                int rd = (packed >> 4) & 0x0F;
                int rs = packed & 0x0F;
                checkGPRs(ip, rd, rs);
                int val = ctx.bus().readByte(regs.get(rs));
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }
            case Opcode.LDWI -> {
                int packed = fetch(ctx, ip + 1);
                int rd = (packed >> 4) & 0x0F;
                int rs = packed & 0x0F;
                checkGPRs(ip, rd, rs);
                int val = ctx.bus().readWord(regs.get(rs));
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }
            case Opcode.STBI -> {
                int packed = fetch(ctx, ip + 1);
                int rv = (packed >> 4) & 0x0F;
                int ra = packed & 0x0F;
                checkGPRs(ip, rv, ra);
                ctx.bus().writeByte(regs.get(ra), regs.get(rv));
                return false;
            }
            case Opcode.STWI -> {
                int packed = fetch(ctx, ip + 1);
                int rv = (packed >> 4) & 0x0F;
                int ra = packed & 0x0F;
                checkGPRs(ip, rv, ra);
                ctx.bus().writeWord(regs.get(ra), regs.get(rv));
                return false;
            }
            case Opcode.COPY -> {
                // BLOCK: COPY srcAddr, dstAddr, len — a block transfer, delegated to
                // the DMA controller (D17) so the CPU never bypasses it.
                int srcAddr = u16(ctx, ip + 1);
                int dstAddr = u16(ctx, ip + 3);
                int len = u16(ctx, ip + 5);
                ctx.dma().copyBlock(srcAddr, dstAddr, len);
                return false;
            }
            case Opcode.MOVE -> {
                int srcAddr = u16(ctx, ip + 1);
                int dstAddr = u16(ctx, ip + 3);
                int len = u16(ctx, ip + 5);
                // MOVE allows overlap — the DMA's move has memmove semantics.
                ctx.dma().moveBlock(srcAddr, dstAddr, len);
                return false;
            }

            // ===== Arithmetic =====
            case Opcode.ADD -> { executeArith(ctx, ip, (a, b) -> a + b, true, false); return false; }
            case Opcode.SUB -> { executeArith(ctx, ip, (a, b) -> a - b, true, true); return false; }
            case Opcode.MUL -> { executeMul(ctx, ip); return false; }
            case Opcode.DIV -> { executeDiv(ctx, ip, true); return false; }
            case Opcode.MOD -> { executeDiv(ctx, ip, false); return false; }

            // ===== Logic =====
            case Opcode.AND -> { executeLogic(ctx, ip, (a, b) -> a & b); return false; }
            case Opcode.OR  -> { executeLogic(ctx, ip, (a, b) -> a | b); return false; }
            case Opcode.XOR -> { executeLogic(ctx, ip, (a, b) -> a ^ b); return false; }
            case Opcode.NOT -> { executeNot(ctx, ip); return false; }

            // ===== Shifts =====
            case Opcode.SHL -> { executeShift(ctx, ip, true, false); return false; }
            case Opcode.SHR -> { executeShift(ctx, ip, false, false); return false; }
            case Opcode.SAR -> { executeShift(ctx, ip, false, true); return false; }

            // ===== Comparison =====
            case Opcode.CMP  -> { executeCmp(ctx, ip, true); return false; }
            case Opcode.CMPU -> { executeCmp(ctx, ip, false); return false; }

            // ===== Flow control =====
            case Opcode.JMP -> { executeJump(ctx, ip, true, false); return true; }
            case Opcode.JZ  -> { return executeJump(ctx, ip, regs.getZ(), false); }
            case Opcode.JNZ -> { return executeJump(ctx, ip, !regs.getZ(), false); }
            case Opcode.JC  -> { return executeJump(ctx, ip, regs.getC(), false); }
            case Opcode.JV  -> { return executeJump(ctx, ip, regs.getV(), false); }
            case Opcode.CALL -> { executeCall(ctx, regs, ip); return true; }
            case Opcode.RET  -> { executeReturn(ctx, regs, ip); return true; }

            // ===== Services =====
            case Opcode.HOST -> { executeHost(ctx, regs, ip); return false; }

            // ===== Control =====
            case Opcode.HALT -> {
                // Halt: stop execution normally
                regs.set(RegisterFile.IP, program.codeSize()); // Force exit
                return true;
            }
            case Opcode.TRAP -> {
                int code8 = fetch(ctx, ip + 1);
                throw new VmException(VmException.Code.ARITHMETIC_FAULT, "TRAP 0x" + Integer.toHexString(code8), ip);
            }

            default -> throw VmException.invalidOpcode(opcode, ip);
        }
    }

    // ===== Instruction implementations =====

    private void checkGPR(int reg, int ip) {
        if (!RegisterFile.isGPR(reg)) {
            throw VmException.invalidOperand("Not a GPR: R" + reg, ip);
        }
    }

    /** Validates several register operands of one instruction (reports the real IP). */
    private void checkGPRs(int ip, int... regs) {
        for (int r : regs) checkGPR(r, ip);
    }

    private boolean executeArith(ExecutionContext ctx, int ip, IntBinaryOperator op, boolean signed, boolean isSub) {
        RegisterFile regs = ctx.registers();
        int packed = fetch(ctx, ip + 1);
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPRs(ip, rd, rs);
        int a = regs.get(rd);
        int b = regs.get(rs);
        int result = op.applyAsInt(a, b) & 0xFFFF;
        regs.set(rd, result);
        if (signed) {
            regs.updateAddFlags(a, b, result, isSub);
        }
        return false;
    }

    private boolean executeMul(ExecutionContext ctx, int ip) {
        RegisterFile regs = ctx.registers();
        int packed = fetch(ctx, ip + 1);
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPRs(ip, rd, rs);
        int a = regs.get(rd);
        int b = regs.get(rs);
        int result = (a * b) & 0xFFFF;
        regs.set(rd, result);
        regs.updateMulFlags(a, b, result);
        return false;
    }

    private boolean executeDiv(ExecutionContext ctx, int ip, boolean isDiv) {
        RegisterFile regs = ctx.registers();
        int packed = fetch(ctx, ip + 1);
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPRs(ip, rd, rs);
        int a = regs.get(rd);
        int b = regs.get(rs);
        if (b == 0) {
            throw VmException.divisionByZero(ip);
        }
        // Special case: -32768 / -1 overflow
        if (isDiv && a == 0x8000 && b == 0xFFFF) {
            throw VmException.arithmeticFault("Signed division overflow: -32768 / -1", ip);
        }
        int sa = (short) a;
        int sb = (short) b;
        int result = isDiv ? (sa / sb) : (sa % sb);
        regs.set(rd, result & 0xFFFF);
        regs.updateDivFlags(result);
        return false;
    }

    private boolean executeLogic(ExecutionContext ctx, int ip, IntBinaryOperator op) {
        RegisterFile regs = ctx.registers();
        int packed = fetch(ctx, ip + 1);
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPRs(ip, rd, rs);
        int a = regs.get(rd);
        int b = regs.get(rs);
        int result = op.applyAsInt(a, b) & 0xFFFF;
        regs.set(rd, result);
        regs.updateLogicFlags(result);
        return false;
    }

    private boolean executeNot(ExecutionContext ctx, int ip) {
        RegisterFile regs = ctx.registers();
        int rd = fetch(ctx, ip + 1);
        checkGPR(rd, ip);
        int a = regs.get(rd);
        int result = (~a) & 0xFFFF;
        regs.set(rd, result);
        regs.updateLogicFlags(result);
        return false;
    }

    private void executeShift(ExecutionContext ctx, int ip, boolean left, boolean arithmetic) {
        RegisterFile regs = ctx.registers();
        int packed = fetch(ctx, ip + 1);
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPRs(ip, rd, rs);
        int value = regs.get(rd);
        int rawCount = regs.get(rs); // Full 16-bit value
        if (rawCount < 0 || rawCount > 15) {
            throw VmException.invalidShiftCount(rawCount, ip);
        }
        int count = rawCount & 0xF; // Only low 4 bits (now validated to be 0-15)
        int result;
        int lastBit = 0;
        if (left) {
            result = (value << count) & 0xFFFF;
            lastBit = count > 0 ? (value >> (16 - count)) & 1 : 0;
        } else if (arithmetic) {
            lastBit = count > 0 ? (value >> (count - 1)) & 1 : 0;
            result = (value >> count) & 0xFFFF; // Java >> is arithmetic for signed
            // Proper SAR: sign-extend
            int sign = value & 0x8000;
            result = ((value & 0xFFFF) >> count) | (sign != 0 ? (~0 << (16 - count)) : 0);
        } else {
            lastBit = count > 0 ? (value >> (count - 1)) & 1 : 0;
            result = (value >>> count) & 0xFFFF; // Logical shift right
        }
        regs.set(rd, result);
        regs.updateShiftFlags(result, lastBit);
    }

    private void executeCmp(ExecutionContext ctx, int ip, boolean signed) {
        RegisterFile regs = ctx.registers();
        int packed = fetch(ctx, ip + 1);
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPRs(ip, rd, rs);
        int a = regs.get(rd);
        int b = regs.get(rs);
        if (signed) {
            regs.updateCmpFlags(a, b);
        } else {
            regs.updateCmpuFlags(a, b);
        }
    }

    private boolean executeJump(ExecutionContext ctx, int ip, boolean condition, boolean unconditional) {
        RegisterFile regs = ctx.registers();
        if (!unconditional && !condition) {
            return false; // Condition not met, don't jump, let main loop advance IP
        }
        // BR16: signed 16-bit relative offset from end of instruction
        int offset = u16(ctx, ip + 1);
        // Sign extend
        if ((offset & 0x8000) != 0) {
            offset |= 0xFFFF0000;
        }
        int target = ip + 3 + offset; // IP after this instruction
        if (target < 0 || target >= program.codeSize()) {
            throw VmException.invalidJump(target, ip);
        }
        // Verify target is at instruction boundary (simplified: just check it's valid)
        regs.set(RegisterFile.IP, target);
        return true;
    }

    private void executeCall(ExecutionContext ctx, RegisterFile regs, int ip) {
        int offset = u16(ctx, ip + 1);
        if ((offset & 0x8000) != 0) offset |= 0xFFFF0000;
        int target = ip + 3 + offset;
        if (target < 0 || target >= program.codeSize()) {
            throw VmException.invalidJump(target, ip);
        }

        // Save the caller's frame pointer, then push the return address.
        int callerFramePointer = regs.get(RegisterFile.FP);
        ctx.enterCall(callerFramePointer);

        // Push return address (IP after CALL) onto stack via SoftMMU
        int sp = regs.get(RegisterFile.SP);
        if (sp < 2) {
            throw new VmException(VmException.Code.STACK_LIMIT, "Stack overflow on CALL", ip);
        }
        sp -= 2;
        ctx.bus().writeWord(sp, ip + 3); // Return address
        regs.set(RegisterFile.SP, sp);

        // Establish the new frame: FP is the base of the callee's frame.
        regs.set(RegisterFile.FP, sp);

        // Jump to target
        regs.set(RegisterFile.IP, target);
    }

    private boolean executeReturn(ExecutionContext ctx, RegisterFile regs, int ip) {
        // Leave the frame: this also restores the caller's frame pointer,
        // as required by spec §7.4/§8.7 ("RET restaura la dirección y el frame").
        int restoredFramePointer = ctx.exitCall();

        // Pop return address from stack via SoftMMU
        int sp = regs.get(RegisterFile.SP);

        if (sp + 2 > SoftMMU.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.STACK_LIMIT, "Stack underflow on RET", ip);
        }
        int retAddr = ctx.bus().readWord(sp);
        sp += 2;
        regs.set(RegisterFile.SP, sp);
        regs.set(RegisterFile.FP, restoredFramePointer);
        regs.set(RegisterFile.IP, retAddr);
        return true;
    }

    private boolean executeHost(ExecutionContext ctx, RegisterFile regs, int ip) {
        // HOST ABI v2 (syscall-style register convention):
        //   R0 = op       service ID
        //   R1 = options  service-specific parameter
        //   R2 = base     vaddr of the argument buffer (full address)
        //   R3 = length   bytes (in: requested · out: transferred)
        //   R4            reserved for the scalar result (extras start at R5)
        // Returns:
        //   R0 = status code, R3 = bytes transferred, R4 = scalar result.
        // R1 (options) and R2 (base) are left untouched.

        int serviceId = regs.get(RegisterFile.R0);
        int options = regs.get(RegisterFile.R1);
        int base = regs.get(RegisterFile.R2);
        int length = regs.get(RegisterFile.R3);

        ServiceResult result = serviceRegistry.invoke(ctx, serviceId, base, length, options);

        regs.set(RegisterFile.R0, result.status().code());
        regs.set(RegisterFile.R3, result.bytesTransferred());
        regs.set(RegisterFile.R4, result.result());
        return false;
    }

    /** Reads a big-endian 16-bit operand from the code stream (through the MMU). */
    private static int u16(ExecutionContext ctx, int offset) {
        return (fetch(ctx, offset) << 8) | fetch(ctx, offset + 1);
    }

    /**
     * Emits a compact, sparse trace line for memory/block instructions only.
     * <p>
     * Instead of dumping memory (which would be unaffordable per cycle), it records
     * just the affected address(es) and value(s). Called after execution so that
     * loads report the value actually read into the destination register, and
     * stores report the value written. Block operations additionally emit a
     * 16-bit additive checksum of the source and destination ranges, which exposes
     * copy/move corruption directly (for a non-overlapping COPY, {@code sumSrc}
     * must equal {@code sumDst}).
     * </p>
     */
    private void logMemoryTrace(ExecutionContext ctx, int opcode, int ip) {
        RegisterFile regs = ctx.registers();
        switch (opcode) {
            case Opcode.LDB -> {
                int rd = fetch(ctx, ip + 1);
                int addr = u16(ctx, ip + 2);
                LoggerUtils.Module.debug("iflow", "MEM RD8  [0x%04X]=0x%02X -> R%d", addr, regs.get(rd) & 0xFF, rd);
            }
            case Opcode.LDW -> {
                int rd = fetch(ctx, ip + 1);
                int addr = u16(ctx, ip + 2);
                LoggerUtils.Module.debug("iflow", "MEM RD16 [0x%04X]=0x%04X -> R%d", addr, regs.get(rd) & 0xFFFF, rd);
            }
            case Opcode.STB -> {
                int rs = fetch(ctx, ip + 1);
                int addr = u16(ctx, ip + 2);
                LoggerUtils.Module.debug("iflow", "MEM WR8  [0x%04X]<-0x%02X (R%d)", addr, regs.get(rs) & 0xFF, rs);
            }
            case Opcode.STW -> {
                int rs = fetch(ctx, ip + 1);
                int addr = u16(ctx, ip + 2);
                LoggerUtils.Module.debug("iflow", "MEM WR16 [0x%04X]<-0x%04X (R%d)", addr, regs.get(rs) & 0xFFFF, rs);
            }
            case Opcode.LDBI, Opcode.LDWI, Opcode.STBI, Opcode.STWI -> {
                int packed = fetch(ctx, ip + 1);
                int r0 = (packed >> 4) & 0x0F;
                int r1 = packed & 0x0F;
                int addr = regs.get(r1) & 0xFFFF;
                String mnemonic = switch (opcode) {
                    case Opcode.LDBI -> "MEM RD8 ";
                    case Opcode.LDWI -> "MEM RD16";
                    case Opcode.STBI -> "MEM WR8 ";
                    default -> "MEM WR16";
                };
                LoggerUtils.Module.debug("iflow", "%s [0x%04X] (indirect R%d) R%d", mnemonic, addr, r1, r0);
            }
            case Opcode.COPY, Opcode.MOVE -> {
                String name = (opcode == Opcode.COPY) ? "COPY" : "MOVE";
                int src = u16(ctx, ip + 1);
                int dst = u16(ctx, ip + 3);
                int len = u16(ctx, ip + 5);
                if (len == 0) {
                    LoggerUtils.Module.debug("iflow", "MEM %s len=0 (no-op) src=0x%04X dst=0x%04X", name, src, dst);
                    return;
                }
                int sumSrc = blockChecksum(ctx, src, len);
                int sumDst = blockChecksum(ctx, dst, len);
                LoggerUtils.Module.debug("iflow",
                    "MEM %s [0x%04X..0x%04X] -> [0x%04X..0x%04X] len=%d sumSrc=0x%04X sumDst=0x%04X",
                    name, src, src + len - 1, dst, dst + len - 1, len, sumSrc, sumDst);
            }
            default -> { /* not a memory instruction: nothing to trace */ }
        }
    }

    /** Compact register + flag dump for the {@code VM_CYCLE} debug line. */
    static String formatRegisters(RegisterFile regs) {
        StringBuilder sb = new StringBuilder();
        sb.append("R0=0x").append(String.format("%04X", regs.get(0))).append(" ");
        sb.append("R1=0x").append(String.format("%04X", regs.get(1))).append(" ");
        sb.append("R2=0x").append(String.format("%04X", regs.get(2))).append(" ");
        sb.append("R3=0x").append(String.format("%04X", regs.get(3))).append(" ");
        sb.append("R4=0x").append(String.format("%04X", regs.get(4))).append(" ");
        sb.append("R5=0x").append(String.format("%04X", regs.get(5))).append(" ");
        sb.append("R6=0x").append(String.format("%04X", regs.get(6))).append(" ");
        sb.append("R7=0x").append(String.format("%04X", regs.get(7))).append(" ");
        sb.append("IP=0x").append(String.format("%04X", regs.get(8))).append(" ");
        sb.append("SP=0x").append(String.format("%04X", regs.get(9))).append(" ");
        sb.append("FP=0x").append(String.format("%04X", regs.get(10))).append(" ");
        sb.append("FL=0x").append(String.format("%04X", regs.get(11)));
        sb.append(String.format("(Z=%b N=%b C=%b V=%b)",
            regs.getZ(), regs.getN(), regs.getC(), regs.getV()));
        return sb.toString();
    }

    /** 16-bit additive checksum over {@code len} bytes at {@code addr}. */
    private int blockChecksum(ExecutionContext ctx, int addr, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum = (sum + (ctx.bus().readByte(addr + i) & 0xFF)) & 0xFFFF;
        }
        return sum;
    }
}
