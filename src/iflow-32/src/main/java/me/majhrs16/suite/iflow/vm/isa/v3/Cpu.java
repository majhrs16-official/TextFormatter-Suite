package me.majhrs16.suite.iflow.vm.isa.v3;

import java.util.Objects;

/**
 * Single-vCPU execution core of the v3 ISA.
 *
 * <p>The core fetches and decodes one instruction per {@link #step()}, executes
 * it, and turns synchronous faults into architectural traps. Address
 * translation, bus decoding and instruction-fetch permissions belong to the
 * {@link InstructionReader} and {@link DataMemory} implementations.</p>
 */
public final class Cpu {

    private static final long ADDRESS_SPACE_END = 0xFFFF_FFFFL;

    private final InstructionDecoder decoder = new InstructionDecoder();
    private final InstructionReader instructionReader;
    private final DataMemory memory;
    private final TimeSource timeSource;
    private final HostHandler hostHandler;
    private final InterruptController interrupts;
    private final RegisterFile registers = new RegisterFile();
    private final CsrFile csrs;

    private int pc;
    private long cycles;
    private boolean halted;
    private Opcode lastOpcode;

    public Cpu(int resetVector,
               InstructionReader instructionReader,
               DataMemory memory,
               TimeSource timeSource) {
        this(resetVector, instructionReader, memory, timeSource, null);
    }

    public Cpu(int resetVector,
               InstructionReader instructionReader,
               DataMemory memory,
               TimeSource timeSource,
               HostHandler hostHandler) {
        this(resetVector, instructionReader, memory, timeSource, hostHandler, null);
    }

    public Cpu(int resetVector,
               InstructionReader instructionReader,
               DataMemory memory,
               TimeSource timeSource,
               HostHandler hostHandler,
               InterruptController interrupts) {
        this(resetVector, instructionReader, memory, timeSource, hostHandler, interrupts,
                new CsrFile());
    }

    public Cpu(int resetVector,
               InstructionReader instructionReader,
               DataMemory memory,
               TimeSource timeSource,
               HostHandler hostHandler,
               InterruptController interrupts,
               CsrFile csrs) {
        this.instructionReader = Objects.requireNonNull(instructionReader, "instructionReader");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.hostHandler = hostHandler;
        this.interrupts = interrupts;
        this.csrs = Objects.requireNonNull(csrs, "csrs");
        reset(resetVector);
    }

    public void reset(int resetVector) {
        pc = resetVector;
        cycles = 0;
        halted = false;
        lastOpcode = null;
        clearReservation();
        registers.reset();
        csrs.reset();
    }

    public int pc() {
        return pc;
    }

    public long cycles() {
        return cycles;
    }

    public boolean halted() {
        return halted;
    }

    /**
     * Opcode attempted by the latest {@link #step()}, or {@code null} when the
     * step only delivered an interrupt or failed before decoding.
     */
    public Opcode lastOpcode() {
        return lastOpcode;
    }

    public RegisterFile registers() {
        return registers;
    }

    public CsrFile csrs() {
        return csrs;
    }

    /**
     * Executes one instruction.
     *
     * @return {@code true} if an instruction was attempted, {@code false} if
     *         the CPU is halted
     */
    public boolean step() {
        lastOpcode = null;
        if (deliverInterrupt()) {
            return true;
        }
        if (halted) {
            return false;
        }
        try {
            Instruction instruction = decoder.decode(pc, instructionReader);
            lastOpcode = instruction.opcode();
            execute(instruction);
            cycles++;
            return true;
        } catch (MachineFault fault) {
            takeTrap(fault);
            return true;
        }
    }

    private boolean deliverInterrupt() {
        if (interrupts == null) {
            return false;
        }
        interrupts.poll();
        if (!csrs.status().interruptEnable()) {
            return false;
        }
        int line = interrupts.takePending();
        if (line < 0) {
            return false;
        }
        if (line > 31) {
            throw new IllegalStateException("Interrupt source returned line " + line);
        }
        halted = false;
        enterTrap(pc, 16 + line, null);
        return true;
    }

    private void execute(Instruction instruction) {
        if (!csrs.status().supervisor() && !csrs.status().mmuEnabled()) {
            throw MachineFault.privilege(instruction.pc(),
                    "user mode requires the MMU");
        }

        if (requiredPrivilege(instruction.opcode()) == Privilege.SUPERVISOR
                && !csrs.status().supervisor()) {
            throw MachineFault.privilege(instruction.pc(),
                    instruction.opcode().mnemonic() + " requires supervisor mode");
        }

        switch (instruction.opcode()) {
            case MOV -> executeMov(instruction);
            case LEA -> executeLea(instruction);
            case PUSH -> executePush(instruction);
            case POP -> executePop(instruction);

            case ADD, ADC, SUB, AND, OR, XOR, SHL, SHR, SAR,
                    MUL, DIV, DIVU, MOD, MODU, CMP, TEST -> executeAlu(instruction);

            case JMP -> jump(instruction);
            case JCC -> executeJcc(instruction);
            case CALL -> executeCall(instruction);
            case RET -> pc = registers.get(Register.LR);
            case ECALL -> throw new MachineFault(
                    csrs.status().supervisor()
                            ? MachineFault.Code.ECALL_SUPERVISOR
                            : MachineFault.Code.ECALL_USER,
                    instruction.pc() + 3,
                    "ecall");
            case ERET -> executeEret(instruction);
            case HALT -> executeHalt(instruction);
            case BREAK -> throw MachineFault.breakpoint(instruction.pc(), "breakpoint");
            case NOP -> advance(instruction);

            case HOST -> executeHost(instruction);
            case RDCYCLE -> executeRdcycle(instruction);
            case RDTIME -> executeRdtime(instruction);
            case CSRRW, CSRRS, CSRRC, CSRR, CSRW -> executeCsr(instruction);
            case SFENCE_VMA -> {
                memory.flushTlb();
                advance(instruction);
            }
            case FENCE -> {
                memory.fence();
                advance(instruction);
            }
            case LR_W -> executeLrW(instruction);
            case SC_W -> executeScW(instruction);
            case AMOADD_W, AMOSWAP_W, AMOAND_W, AMOOR_W -> executeAmo(instruction);
            default -> throw new IllegalStateException("Unhandled v3 opcode: " + instruction.opcode());
        }
    }

    private void executeMov(Instruction instruction) {
        switch (instruction.form()) {
            case REGISTER -> {
                registers.set(instruction.rd(),
                        truncate(registers.get(instruction.rs()), instruction.size()));
                advance(instruction);
            }
            case IMMEDIATE -> {
                registers.set(instruction.rd(), instruction.immediate());
                advance(instruction);
            }
            case LOAD -> {
                int address = memoryAddress(instruction);
                checkAlignment(instruction, address);
                registers.set(instruction.rd(), read(address, instruction.size(), instruction.pc()));
                advance(instruction);
            }
            case STORE -> {
                int address = memoryAddress(instruction);
                checkAlignment(instruction, address);
                write(address, instruction.size(), registers.get(instruction.rd()), instruction.pc());
                advance(instruction);
            }
        }
    }

    private void executeLea(Instruction instruction) {
        registers.set(instruction.rd(), memoryAddress(instruction));
        advance(instruction);
    }

    private void executePush(Instruction instruction) {
        int sp = registers.get(Register.SP);
        checkStackAlignment(instruction, sp);
        int nextSp = sp - Integer.BYTES;
        write(nextSp, MemorySize.WORD, registers.get(instruction.rs()), instruction.pc());
        registers.set(Register.SP, nextSp);
        advance(instruction);
    }

    private void executePop(Instruction instruction) {
        int sp = registers.get(Register.SP);
        checkStackAlignment(instruction, sp);
        int value = read(sp, MemorySize.WORD, instruction.pc());
        registers.set(Register.SP, sp + Integer.BYTES);
        registers.set(instruction.rd(), value);
        advance(instruction);
    }

    private void executeAlu(Instruction instruction) {
        int left = registers.get(instruction.rd());
        int right = instruction.form() == InstructionForm.IMMEDIATE
                ? instruction.immediate()
                : registers.get(instruction.rs());
        Alu.Result result = switch (instruction.opcode()) {
            case ADD -> Alu.add(left, right);
            case ADC -> Alu.add(left, right, csrs.flags().carry());
            case SUB -> Alu.sub(left, right);
            case AND -> Alu.and(left, right);
            case OR -> Alu.or(left, right);
            case XOR -> Alu.xor(left, right);
            case SHL -> Alu.shl(left, right, csrs.flags());
            case SHR -> Alu.shr(left, right, csrs.flags());
            case SAR -> Alu.sar(left, right, csrs.flags());
            case MUL -> Alu.mul(left, right);
            case DIV -> Alu.div(left, right, instruction.pc());
            case DIVU -> Alu.divu(left, right, instruction.pc());
            case MOD -> Alu.mod(left, right, instruction.pc());
            case MODU -> Alu.modu(left, right, instruction.pc());
            case CMP -> Alu.cmp(left, right);
            case TEST -> Alu.test(left, right);
            default -> throw new IllegalStateException("Unhandled ALU opcode: " + instruction.opcode());
        };

        csrs.setFlags(result.flags());
        if (instruction.opcode() != Opcode.CMP && instruction.opcode() != Opcode.TEST) {
            registers.set(instruction.rd(), result.value());
        }
        advance(instruction);
    }

    private void jump(Instruction instruction) {
        pc = jumpTarget(instruction);
    }

    private void executeJcc(Instruction instruction) {
        if (condition(instruction.rs(), csrs.flags())) {
            pc = instruction.pc() + instruction.length() + instruction.immediate();
        } else {
            advance(instruction);
        }
    }

    private void executeCall(Instruction instruction) {
        int target = jumpTarget(instruction);
        registers.set(Register.LR, instruction.pc() + instruction.length());
        pc = target;
    }

    private void executeEret(Instruction instruction) {
        if (!csrs.status().trap()) {
            throw MachineFault.illegalInstruction(instruction.pc(), "ERET without an active trap");
        }
        Status restored = csrs.status().eret();
        if (!restored.supervisor() && !restored.mmuEnabled()) {
            throw MachineFault.privilege(instruction.pc(),
                    "ERET to user mode requires the MMU");
        }
        clearReservation();
        csrs.setStatus(restored);
        pc = csrs.epc();
    }

    private void executeHalt(Instruction instruction) {
        if (!csrs.status().interruptEnable()) {
            throw MachineFault.illegalInstruction(instruction.pc(),
                    "HALT requires interrupts enabled");
        }
        clearReservation();
        pc = instruction.pc() + instruction.length();
        halted = true;
    }

    private void executeHost(Instruction instruction) {
        int flags = registers.get(Register.R1);
        int argumentLength = registers.get(Register.R3);
        int resultCapacity = registers.get(Register.R5);
        if (flags != 0
                || argumentLength < 0 || argumentLength > HostCall.MAX_BUFFER
                || resultCapacity < 0 || resultCapacity > HostCall.MAX_BUFFER) {
            registers.set(Register.R0, HostStatus.INVALID_ARGUMENT);
            registers.set(Register.R1, 0);
            advance(instruction);
            return;
        }
        if (crossesAddressSpace(registers.get(Register.R2), argumentLength)
                || crossesAddressSpace(registers.get(Register.R4), resultCapacity)) {
            registers.set(Register.R0, HostStatus.FAULT);
            registers.set(Register.R1, 0);
            advance(instruction);
            return;
        }
        if (hostHandler == null) {
            throw new IllegalStateException("HOST handler is not configured");
        }

        HostCall call = new HostCall(
                instruction.pc(),
                csrs.status().privilege(),
                registers.get(Register.R0),
                flags,
                registers.get(Register.R2),
                argumentLength,
                registers.get(Register.R4),
                resultCapacity,
                memory,
                csrs.pident(),
                csrs.mident());
        HostResult result;
        try {
            result = Objects.requireNonNull(hostHandler.call(call), "host result");
        } catch (MemoryFault fault) {
            registers.set(Register.R0, HostStatus.FAULT);
            registers.set(Register.R1, 0);
            advance(instruction);
            return;
        }
        registers.set(Register.R0, result.status());
        registers.set(Register.R1, result.resultLength());
        advance(instruction);
    }

    private void executeRdcycle(Instruction instruction) {
        writeCounter(instruction, cycles);
    }

    private void executeRdtime(Instruction instruction) {
        writeCounter(instruction, timeSource.timeNanos());
    }

    private void writeCounter(Instruction instruction, long value) {
        registers.set(instruction.rd(), (int) value);
        registers.set(instruction.rs(), (int) (value >>> 32));
        advance(instruction);
    }

    private void executeCsr(Instruction instruction) {
        Csr csr = Csr.fromAddress(instruction.immediate());
        if (csr == null) {
            throw MachineFault.illegalInstruction(instruction.pc(),
                    "undefined CSR 0x" + Integer.toHexString(instruction.immediate()));
        }
        int source = registers.get(instruction.rs());
        int old = csrs.read(csr);

        switch (instruction.opcode()) {
            case CSRRW -> {
                if (instruction.rs() != 0) {
                    csrs.write(csr, source, instruction.pc());
                }
                if (instruction.rd() != 0) {
                    registers.set(instruction.rd(), old);
                }
            }
            case CSRRS -> {
                if (instruction.rs() != 0) {
                    csrs.write(csr, old | source, instruction.pc());
                }
                if (instruction.rd() != 0) {
                    registers.set(instruction.rd(), old);
                }
            }
            case CSRRC -> {
                if (instruction.rs() != 0) {
                    csrs.write(csr, old & ~source, instruction.pc());
                }
                if (instruction.rd() != 0) {
                    registers.set(instruction.rd(), old);
                }
            }
            case CSRR -> {
                if (instruction.rd() != 0) {
                    registers.set(instruction.rd(), old);
                }
            }
            case CSRW -> {
                if (instruction.rs() != 0) {
                    csrs.write(csr, source, instruction.pc());
                }
            }
            default -> throw new IllegalStateException("Unhandled CSR opcode: " + instruction.opcode());
        }
        advance(instruction);
    }

    private void executeLrW(Instruction instruction) {
        requireAtomicWord(instruction);
        int address = memoryAddress(instruction);
        registers.set(instruction.rd(), loadLinked(address, instruction.pc()));
        advance(instruction);
    }

    private void executeScW(Instruction instruction) {
        requireAtomicWord(instruction);
        requireUnindexedAtomic(instruction);
        int value = registers.get(instruction.rs());
        boolean stored = storeConditional(
                memoryAddress(instruction), value, instruction.pc());
        registers.set(instruction.rd(), stored ? 1 : 0);
        advance(instruction);
    }

    private void executeAmo(Instruction instruction) {
        requireAtomicWord(instruction);
        requireUnindexedAtomic(instruction);
        AtomicMemory.Operation operation = switch (instruction.opcode()) {
            case AMOADD_W -> AtomicMemory.Operation.ADD;
            case AMOSWAP_W -> AtomicMemory.Operation.SWAP;
            case AMOAND_W -> AtomicMemory.Operation.AND;
            case AMOOR_W -> AtomicMemory.Operation.OR;
            default -> throw new IllegalStateException(
                    "Unhandled atomic operation: " + instruction.opcode());
        };
        int operand = registers.get(instruction.rs());
        int previous = atomic(
                memoryAddress(instruction), operation, operand, instruction.pc());
        registers.set(instruction.rd(), previous);
        advance(instruction);
    }

    private int loadLinked(int address, int pc) {
        try {
            return atomicMemory().loadLinked(address);
        } catch (MemoryFault fault) {
            throw new MachineFault(fault.code(), pc, fault.badAddress(), fault.detail());
        }
    }

    private boolean storeConditional(int address, int value, int pc) {
        try {
            return atomicMemory().storeConditional(address, value);
        } catch (MemoryFault fault) {
            throw new MachineFault(fault.code(), pc, fault.badAddress(), fault.detail());
        }
    }

    private int atomic(int address, AtomicMemory.Operation operation, int operand, int pc) {
        try {
            return atomicMemory().atomic(address, operation, operand);
        } catch (MemoryFault fault) {
            throw new MachineFault(fault.code(), pc, fault.badAddress(), fault.detail());
        }
    }

    private void requireAtomicWord(Instruction instruction) {
        if (instruction.size() != MemorySize.WORD) {
            throw MachineFault.illegalInstruction(instruction.pc(),
                    instruction.opcode().mnemonic() + " requires a 32-bit access");
        }
    }

    private void requireUnindexedAtomic(Instruction instruction) {
        if (instruction.indexed()) {
            throw MachineFault.illegalInstruction(instruction.pc(),
                    instruction.opcode().mnemonic() + " does not accept an index register");
        }
    }

    private AtomicMemory atomicMemory() {
        if (!(memory instanceof AtomicMemory atomic)) {
            throw new IllegalStateException(
                    "Data memory does not implement AtomicMemory");
        }
        return atomic;
    }

    private void clearReservation() {
        if (memory instanceof AtomicMemory atomic) {
            atomic.clearReservation();
        }
    }

    private void takeTrap(MachineFault fault) {
        if (csrs.tvec() == 0) {
            throw new IllegalStateException("UNHANDLED_TRAP: " + fault.getMessage(), fault);
        }
        enterTrap(fault.pc(), fault.code().vector(), fault.badAddress());
    }

    private void enterTrap(int instructionPc, int vector, Integer badAddress) {
        int tvec = csrs.tvec();
        if (tvec == 0) {
            throw new IllegalStateException("UNHANDLED_TRAP: TVEC is zero");
        }
        if (csrs.status().trap()) {
            if (csrs.cause() == MachineFault.Code.DOUBLE_FAULT.vector()) {
                throw new IllegalStateException("Double fault during double fault");
            }
            vector = MachineFault.Code.DOUBLE_FAULT.vector();
        }
        clearReservation();
        csrs.enterTrap(instructionPc, vector, badAddress);
        pc = tvec + Integer.BYTES * vector;
    }

    private int memoryAddress(Instruction instruction) {
        int address = registers.get(instruction.base());
        if (instruction.indexed()) {
            address += registers.get(instruction.rs());
        }
        return address + instruction.immediate();
    }

    private void checkAlignment(Instruction instruction, int address) {
        int mask = instruction.memorySizeBytes() - 1;
        if ((address & mask) != 0) {
            throw MachineFault.alignment(instruction.pc(), address,
                    "unaligned " + instruction.memorySizeBytes() + "-byte access");
        }
    }

    private void checkStackAlignment(Instruction instruction, int sp) {
        if ((sp & (Integer.BYTES - 1)) != 0) {
            throw MachineFault.alignment(instruction.pc(), sp,
                    "stack pointer must be 4-byte aligned");
        }
    }

    private int read(int address, MemorySize size, int pc) {
        try {
            return memory.read(address, size);
        } catch (MemoryFault fault) {
            throw new MachineFault(fault.code(), pc, fault.badAddress(), fault.detail());
        }
    }

    private void write(int address, MemorySize size, int value, int pc) {
        try {
            memory.write(address, size, value);
        } catch (MemoryFault fault) {
            throw new MachineFault(fault.code(), pc, fault.badAddress(), fault.detail());
        }
    }

    private static boolean crossesAddressSpace(int address, int length) {
        return length > 0
                && Integer.toUnsignedLong(address) + length - 1 > ADDRESS_SPACE_END;
    }

    private static int truncate(int value, MemorySize size) {
        return switch (size) {
            case BYTE -> value & 0xFF;
            case HALF -> value & 0xFFFF;
            case WORD -> value;
        };
    }

    private int jumpTarget(Instruction instruction) {
        if (instruction.form() == InstructionForm.IMMEDIATE) {
            return instruction.pc() + instruction.length() + instruction.immediate();
        }
        return registers.get(instruction.rs());
    }

    private static boolean condition(int code, Flags flags) {
        return switch (code) {
            case 0 -> flags.zero();
            case 1 -> !flags.zero();
            case 2 -> flags.carry();
            case 3 -> !flags.carry();
            case 4 -> flags.negative();
            case 5 -> !flags.negative();
            case 6 -> flags.overflow();
            case 7 -> !flags.overflow();
            case 8 -> flags.carry() && !flags.zero();
            case 9 -> !flags.carry() || flags.zero();
            case 10 -> flags.negative() == flags.overflow();
            case 11 -> flags.negative() != flags.overflow();
            case 12 -> !flags.zero() && flags.negative() == flags.overflow();
            case 13 -> flags.zero() || flags.negative() != flags.overflow();
            case 14 -> true;
            case 15 -> false;
            default -> throw new IllegalStateException("Invalid Jcc condition: " + code);
        };
    }

    private static Privilege requiredPrivilege(Opcode opcode) {
        return switch (opcode) {
            case CSRRW, CSRRS, CSRRC, CSRR, CSRW, ERET, SFENCE_VMA, HALT ->
                    Privilege.SUPERVISOR;
            default -> Privilege.USER;
        };
    }

    private void advance(Instruction instruction) {
        pc = instruction.pc() + instruction.length();
    }
}
