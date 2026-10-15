package me.majhrs16.suite.iflow.vm.execution;

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
 * Implements the ISA v1 instruction set.
 * Uses flat 64KB address space with SoftMMU (4KB paging).
 * </p>
 */
public final class VmInterpreter {

    private static final int MAX_INSTRUCTION_COST = 1000; // Safety limit

    private final ProgramImage program;
    private final SoftMMU softMMU;
    private final HostServiceRegistry serviceRegistry;

    public VmInterpreter(ProgramImage program, SoftMMU softMMU, HostServiceRegistry serviceRegistry) {
        this.program = Objects.requireNonNull(program);
        this.softMMU = Objects.requireNonNull(softMMU);
        this.serviceRegistry = Objects.requireNonNull(serviceRegistry);
    }

    /**
     * Executes the program from its entry point.
     * @param context the execution context (must be initialized)
     * @throws VmException on execution error
     */
    public void execute(ExecutionContext context) {
        // Set entry point
        context.registers().set(RegisterFile.IP, program.entryPoint());

        // Verify entry point is valid
        if (program.entryPoint() < 0 || program.entryPoint() >= program.codeSize()) {
            throw VmException.invalidJump(program.entryPoint(), program.entryPoint());
        }

        // Main interpreter loop
        while (true) {
            // Check cancellation
            context.checkCancelled();

            // Fetch
            int ip = context.registers().get(RegisterFile.IP);
            if (ip < 0 || ip >= program.codeSize()) {
                throw VmException.invalidJump(ip, ip);
            }

            // Check minimum fuel for next instruction
            context.consumeFuel(1); // Base cost per instruction

            // Decode
            int opcode = program.code()[ip] & 0xFF;
            if (!Opcode.isValid(opcode)) {
                throw VmException.invalidOpcode(opcode, ip);
            }

            int instrSize = Opcode.instructionSize(opcode);
            if (ip + instrSize > program.codeSize()) {
                throw VmException.invalidOperand("Truncated instruction at IP=0x" + Integer.toHexString(ip), ip);
            }

            // Execute
            boolean ipModifiedByInstruction;
            try {
                ipModifiedByInstruction = executeInstruction(context, opcode, ip);
            } catch (VmException e) {
                throw e;
            } catch (Exception e) {
                throw new VmException(VmException.Code.HOST_ERROR, "Execution error: " + e.getMessage(), ip, e);
            }

            // Advance IP (unless instruction modified it)
            int newIp = context.registers().get(RegisterFile.IP);
            if (!ipModifiedByInstruction && newIp == ip) {
                context.registers().set(RegisterFile.IP, ip + instrSize);
            }

            // Check for HALT (IP not advanced past end)
            if (newIp >= program.codeSize()) {
                break;
            }
        }
    }

    private boolean executeInstruction(ExecutionContext ctx, int opcode, int ip) {
        byte[] code = program.code();
        RegisterFile regs = ctx.registers();
        SoftMMU mmu = ctx.softMMU();

        switch (opcode) {
            // ===== Data transfer =====
            case Opcode.LI -> {
                // RI16: LI Rd, imm16
                int rd = code[ip + 1] & 0xFF;
                int imm = ((code[ip + 2] & 0xFF) << 8) | (code[ip + 3] & 0xFF);
                checkGPR(rd, ip);
                regs.set(rd, imm);
                regs.updateZN(imm);
                return false;
            }
            case Opcode.MOV -> {
                // RR: MOV Rd, Rs (packed in one byte: high nibble Rs, low nibble Rd)
                int packed = code[ip + 1] & 0xFF;
                int rd = (packed >> 4) & 0x0F;
                int rs = packed & 0x0F;
                checkGPR(rd, ip);
                checkGPR(rs, ip);
                int val = regs.get(rs);
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }

            // ===== Memory =====
            case Opcode.LDB -> {
                // MEM: LDB Rd, [virtualAddr]
                int rd = code[ip + 1] & 0xFF;
                int virtualAddr = ((code[ip + 2] & 0xFF) << 8) | (code[ip + 3] & 0xFF);
                checkGPR(rd, ip);
                int val = mmu.readByte(virtualAddr);
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }
            case Opcode.LDW -> {
                int rd = code[ip + 1] & 0xFF;
                int virtualAddr = ((code[ip + 2] & 0xFF) << 8) | (code[ip + 3] & 0xFF);
                checkGPR(rd, ip);
                int val = mmu.readWord(virtualAddr);
                regs.set(rd, val);
                regs.updateZN(val);
                return false;
            }
            case Opcode.STB -> {
                int rs = code[ip + 1] & 0xFF;
                int virtualAddr = ((code[ip + 2] & 0xFF) << 8) | (code[ip + 3] & 0xFF);
                checkGPR(rs, ip);
                mmu.writeByte(virtualAddr, regs.get(rs));
                return false;
            }
            case Opcode.STW -> {
                int rs = code[ip + 1] & 0xFF;
                int virtualAddr = ((code[ip + 2] & 0xFF) << 8) | (code[ip + 3] & 0xFF);
                checkGPR(rs, ip);
                mmu.writeWord(virtualAddr, regs.get(rs));
                return false;
            }
            case Opcode.COPY -> {
                // BLOCK: COPY srcAddr, dstAddr, len
                int srcAddr = ((code[ip + 1] & 0xFF) << 8) | (code[ip + 2] & 0xFF);
                int dstAddr = ((code[ip + 3] & 0xFF) << 8) | (code[ip + 4] & 0xFF);
                int len = ((code[ip + 5] & 0xFF) << 8) | (code[ip + 6] & 0xFF);
                // Use MMU block copy
                byte[] buffer = new byte[len];
                ctx.softMMU().readBlock(srcAddr, buffer, 0, len);
                ctx.softMMU().writeBlock(dstAddr, buffer, 0, len);
                return false;
            }
            case Opcode.MOVE -> {
                int srcAddr = ((code[ip + 1] & 0xFF) << 8) | (code[ip + 2] & 0xFF);
                int dstAddr = ((code[ip + 3] & 0xFF) << 8) | (code[ip + 4] & 0xFF);
                int len = ((code[ip + 5] & 0xFF) << 8) | (code[ip + 6] & 0xFF);
                // MOVE allows overlap - use temporary buffer
                byte[] buffer = new byte[len];
                ctx.softMMU().readBlock(srcAddr, buffer, 0, len);
                ctx.softMMU().writeBlock(dstAddr, buffer, 0, len);
                return false;
            }

            // ===== Arithmetic =====
            case Opcode.ADD -> { executeArith(regs, code, ip, (a, b) -> a + b, true, false); return false; }
            case Opcode.SUB -> { executeArith(regs, code, ip, (a, b) -> a - b, true, true); return false; }
            case Opcode.MUL -> { executeMul(regs, code, ip); return false; }
            case Opcode.DIV -> { executeDiv(regs, code, ip, true); return false; }
            case Opcode.MOD -> { executeDiv(regs, code, ip, false); return false; }

            // ===== Logic =====
            case Opcode.AND -> { executeLogic(regs, code, ip, (a, b) -> a & b); return false; }
            case Opcode.OR  -> { executeLogic(regs, code, ip, (a, b) -> a | b); return false; }
            case Opcode.XOR -> { executeLogic(regs, code, ip, (a, b) -> a ^ b); return false; }
            case Opcode.NOT -> { executeNot(regs, code, ip); return false; }

            // ===== Shifts =====
            case Opcode.SHL -> { executeShift(regs, code, ip, true, false); return false; }
            case Opcode.SHR -> { executeShift(regs, code, ip, false, false); return false; }
            case Opcode.SAR -> { executeShift(regs, code, ip, false, true); return false; }

            // ===== Comparison =====
            case Opcode.CMP  -> { executeCmp(regs, code, ip, true); return false; }
            case Opcode.CMPU -> { executeCmp(regs, code, ip, false); return false; }

            // ===== Flow control =====
            case Opcode.JMP -> { executeJump(regs, code, ip, true, false); return true; }
            case Opcode.JZ  -> { return executeJump(regs, code, ip, regs.getZ(), false); }
            case Opcode.JNZ -> { return executeJump(regs, code, ip, !regs.getZ(), false); }
            case Opcode.JC  -> { return executeJump(regs, code, ip, regs.getC(), false); }
            case Opcode.JV  -> { return executeJump(regs, code, ip, regs.getV(), false); }
            case Opcode.CALL -> { executeCall(ctx, regs, code, ip); return true; }
            case Opcode.RET  -> { executeReturn(ctx, regs, code, ip); return true; }

            // ===== Services =====
            case Opcode.HOST -> { executeHost(ctx, regs, code, ip); return false; }

            // ===== Control =====
            case Opcode.HALT -> {
                // Halt: stop execution normally
                regs.set(RegisterFile.IP, program.codeSize()); // Force exit
                return true;
            }
            case Opcode.TRAP -> {
                int code8 = code[ip + 1] & 0xFF;
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

    private void checkGPRs(int... regs) {
        for (int r : regs) checkGPR(r, 0);
    }

    @FunctionalInterface
    private interface IntBinaryOperator {
        int applyAsInt(int a, int b);
    }

    private boolean executeArith(RegisterFile regs, byte[] code, int ip, java.util.function.IntBinaryOperator op, boolean signed, boolean isSub) {
        int packed = code[ip + 1] & 0xFF;
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPR(rd, ip);
        checkGPR(rs, ip);
        int a = regs.get(rd);
        int b = regs.get(rs);
        int result = op.applyAsInt(a, b) & 0xFFFF;
        regs.set(rd, result);
        if (signed) {
            regs.updateAddFlags(a, b, result, isSub);
        }
        return false;
    }

    private boolean executeMul(RegisterFile regs, byte[] code, int ip) {
        int packed = code[ip + 1] & 0xFF;
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPR(rd, ip);
        checkGPR(rs, ip);
        int a = regs.get(rd);
        int b = regs.get(rs);
        int result = (a * b) & 0xFFFF;
        regs.set(rd, result);
        regs.updateMulFlags(a, b, result);
        return false;
    }

    private boolean executeDiv(RegisterFile regs, byte[] code, int ip, boolean isDiv) {
        int packed = code[ip + 1] & 0xFF;
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPR(rd, ip);
        checkGPR(rs, ip);
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

    private boolean executeLogic(RegisterFile regs, byte[] code, int ip, java.util.function.IntBinaryOperator op) {
        int packed = code[ip + 1] & 0xFF;
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPR(rd, ip);
        checkGPR(rs, ip);
        int a = regs.get(rd);
        int b = regs.get(rs);
        int result = op.applyAsInt(a, b) & 0xFFFF;
        regs.set(rd, result);
        regs.updateLogicFlags(result);
        return false;
    }

    private boolean executeNot(RegisterFile regs, byte[] code, int ip) {
        int rd = code[ip + 1] & 0xFF;
        checkGPR(rd, ip);
        int a = regs.get(rd);
        int result = (~a) & 0xFFFF;
        regs.set(rd, result);
        regs.updateLogicFlags(result);
        return false;
    }

    private void executeShift(RegisterFile regs, byte[] code, int ip, boolean left, boolean arithmetic) {
        int packed = code[ip + 1] & 0xFF;
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPR(rd, ip);
        checkGPR(rs, ip);
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

    private void executeCmp(RegisterFile regs, byte[] code, int ip, boolean signed) {
        int packed = code[ip + 1] & 0xFF;
        int rd = (packed >> 4) & 0x0F;
        int rs = packed & 0x0F;
        checkGPR(rd, ip);
        checkGPR(rs, ip);
        int a = regs.get(rd);
        int b = regs.get(rs);
        if (signed) {
            regs.updateCmpFlags(a, b);
        } else {
            regs.updateCmpuFlags(a, b);
        }
    }

    private boolean executeJump(RegisterFile regs, byte[] code, int ip, boolean condition, boolean unconditional) {
        if (!unconditional && !condition) {
            return false; // Condition not met, don't jump, let main loop advance IP
        }
        // BR16: signed 16-bit relative offset from end of instruction
        int offset = ((code[ip + 1] & 0xFF) << 8) | (code[ip + 2] & 0xFF);
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

    private void executeCall(ExecutionContext ctx, RegisterFile regs, byte[] code, int ip) {
        int offset = ((code[ip + 1] & 0xFF) << 8) | (code[ip + 2] & 0xFF);
        if ((offset & 0x8000) != 0) offset |= 0xFFFF0000;
        int target = ip + 3 + offset;
        if (target < 0 || target >= program.codeSize()) {
            throw VmException.invalidJump(target, ip);
        }

        ctx.enterCall();

        // Push return address (IP after CALL) onto stack via SoftMMU
        int sp = regs.get(RegisterFile.SP);
        if (sp < 2) {
            throw new VmException(VmException.Code.STACK_LIMIT, "Stack overflow on CALL", ip);
        }
        sp -= 2;
        softMMU.writeWord(sp, ip + 3); // Return address
        regs.set(RegisterFile.SP, sp);

        // Update frame pointer
        regs.set(RegisterFile.FP, sp);

        // Jump to target
        regs.set(RegisterFile.IP, target);
    }

    private boolean executeReturn(ExecutionContext ctx, RegisterFile regs, byte[] code, int ip) {
        ctx.exitCall();

        // Pop return address from stack via SoftMMU
        int sp = regs.get(RegisterFile.SP);
        int fp = regs.get(RegisterFile.FP);

        if (sp + 2 > SoftMMU.ADDRESS_SPACE_SIZE) {
            throw new VmException(VmException.Code.STACK_LIMIT, "Stack underflow on RET", ip);
        }
        int retAddr = softMMU.readWord(sp);
        sp += 2;
        regs.set(RegisterFile.SP, sp);
        regs.set(RegisterFile.IP, retAddr);
        return true;
    }

    private boolean executeHost(ExecutionContext ctx, RegisterFile regs, byte[] code, int ip) {
        // HOST instruction uses register convention:
        // R0 = service ID
        // R1 = arg handle (virtual address)
        // R2 = arg offset
        // R3 = options / arg length
        // Returns: R0 = status/primary result, R1 = bytes transferred, R3 = aux result

        int serviceId = regs.get(RegisterFile.R0);
        int argHandle = regs.get(RegisterFile.R1); // virtual address
        int argOffset = regs.get(RegisterFile.R2);
        int argLength = 0;
        int options = regs.get(RegisterFile.R3); // R3 contains options/decision code

        ServiceResult result = serviceRegistry.invoke(ctx, serviceId, argHandle, argOffset, argLength, options);

        regs.set(RegisterFile.R0, result.primaryResult());
        regs.set(RegisterFile.R1, result.bytesTransferred());
        regs.set(RegisterFile.R3, result.auxResult());

        if (result.isError()) {
            // Don't throw, let program handle via status code
        }
        return false;
    }
}