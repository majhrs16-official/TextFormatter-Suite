package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VmInterpreterTest {

    private static SoftMMU createSoftMMU() {
        SoftMMU mmu = new SoftMMU();
        // Standard layout, derived from the single-source memory map (VmMemoryMap).
        for (int p = 0; p < VmMemoryMap.CODE_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p,
                VmMemoryMap.CODE_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        }
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.CONSTANTS_BASE),
            VmMemoryMap.CONSTANTS_BASE, PageEntry.PERM_READ);
        for (int p = 0; p < VmMemoryMap.DATA_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.DATA_BASE) + p,
                VmMemoryMap.DATA_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        for (int p = 0; p < VmMemoryMap.STACK_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.STACK_BASE) + p,
                VmMemoryMap.STACK_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
            VmMemoryMap.INPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE),
            VmMemoryMap.OUTPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        return mmu;
    }

    private static VmInterpreter createInterpreter(byte[] code, SoftMMU mm, HostServiceRegistry hsr) {
        ProgramImage prog = ProgramImage.builder()
            .entryPoint(0)
            .code(code)
            .build();
        return new VmInterpreter(prog, mm, hsr);
    }

    private static ExecutionContext createContext(SoftMMU mm, HostServiceRegistry hsr) {
        ExecutionContext ctx = new ExecutionContext()
            .executionId("test")
            .fuelBudget(10000)
            .softMMU(mm)
            .serviceRegistry(hsr);
        ctx.initialize();
        return ctx;
    }

    private static Pair<VmInterpreter, ExecutionContext> createInterpreterAndContext(byte[] code) {
        SoftMMU mm = createSoftMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        return new Pair<>(createInterpreter(code, mm, hsr), createContext(mm, hsr));
    }

    private record Pair<F, S>(F first, S second) {}

    private static byte b(int v) {
        return (byte) v;
    }

    @Test
    void liInstruction() {
        // LI R0, 0x1234
        byte[] code = {Opcode.LI, 0, 0x12, 0x34, Opcode.HALT};
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(0x1234, p.second().registers().get(RegisterFile.R0));
    }

    @Test
    void movInstruction() {
        // LI R1, 0xABCD; MOV R0, R1
        // MOV packed byte: high nibble=rd (dest), low nibble=rs (source)
        // R0 = R1 means rd=0, rs=1 -> packed = 0x01
        byte[] code = {
            Opcode.LI, 1, b(0xAB), b(0xCD),
            Opcode.MOV, (0 << 4) | 1, // R0 = R1 (rd=0, rs=1)
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(0xABCD, p.second().registers().get(RegisterFile.R0));
    }

    @Test
    void addInstruction() {
        // LI R0, 10; LI R1, 20; ADD R0, R1
        byte[] code = {
            Opcode.LI, 0, 0x00, 0x0A,
            Opcode.LI, 1, 0x00, 0x14,
            Opcode.ADD, (0 << 4) | 1, // R0 += R1
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(30, p.second().registers().get(RegisterFile.R0));
        assertFalse(p.second().registers().getZ());
        assertFalse(p.second().registers().getN());
    }

    @Test
    void subInstruction() {
        // LI R0, 30; LI R1, 10; SUB R0, R1
        byte[] code = {
            Opcode.LI, 0, 0x00, 0x1E,
            Opcode.LI, 1, 0x00, 0x0A,
            Opcode.SUB, (0 << 4) | 1, // R0 -= R1
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(20, p.second().registers().get(RegisterFile.R0));
    }

    @Test
    void mulInstruction() {
        // LI R0, 6; LI R1, 7; MUL R0, R1
        byte[] code = {
            Opcode.LI, 0, 0x00, 0x06,
            Opcode.LI, 1, 0x00, 0x07,
            Opcode.MUL, (0 << 4) | 1,
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(42, p.second().registers().get(RegisterFile.R0));
    }

    @Test
    void divInstruction() {
        // LI R0, 100; LI R1, 4; DIV R0, R1
        byte[] code = {
            Opcode.LI, 0, 0x00, 0x64,
            Opcode.LI, 1, 0x00, 0x04,
            Opcode.DIV, (0 << 4) | 1,
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(25, p.second().registers().get(RegisterFile.R0));
    }

    @Test
    void divByZeroThrows() {
        // LI R0, 10; LI R1, 0; DIV R0, R1
        byte[] code = {
            Opcode.LI, 0, 0x00, 0x0A,
            Opcode.LI, 1, 0x00, 0x00,
            Opcode.DIV, (0 << 4) | 1,
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);

        VmException e = assertThrows(VmException.class, () -> p.first().execute(p.second()));
        assertEquals(VmException.Code.DIVISION_BY_ZERO, e.code());
    }

    @Test
    void andOrXorNot() {
        // AND
        var p = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, (byte)0xFF,
            Opcode.LI, 1, 0x00, 0x0F,
            Opcode.AND, (0 << 4) | 1,
            Opcode.HALT
        });
        p.first().execute(p.second());
        assertEquals(0x0F, p.second().registers().get(RegisterFile.R0));

        // OR
        var p2 = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, (byte)0xF0,
            Opcode.LI, 1, 0x00, 0x0F,
            Opcode.OR, (0 << 4) | 1,
            Opcode.HALT
        });
        p2.first().execute(p2.second());
        assertEquals(0xFF, p2.second().registers().get(RegisterFile.R0));

        // XOR
        var p3 = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, (byte)0xFF,
            Opcode.LI, 1, 0x00, 0x0F,
            Opcode.XOR, (0 << 4) | 1,
            Opcode.HALT
        });
        p3.first().execute(p3.second());
        assertEquals(0xF0, p3.second().registers().get(RegisterFile.R0));

        // NOT
        var p4 = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, 0x0F,
            Opcode.NOT, 0,
            Opcode.HALT
        });
        p4.first().execute(p4.second());
        assertEquals(0xFFF0, p4.second().registers().get(RegisterFile.R0));
    }

    @Test
    void shiftInstructions() {
        // SHL: LI R0, 1; LI R1, 4; SHL R0, R1 -> 16
        var p = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, 0x01,
            Opcode.LI, 1, 0x00, 0x04,
            Opcode.SHL, (0 << 4) | 1,
            Opcode.HALT
        });
        p.first().execute(p.second());
        assertEquals(16, p.second().registers().get(RegisterFile.R0));

        // SHR
        var p2 = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, 0x10,
            Opcode.LI, 1, 0x00, 0x02,
            Opcode.SHR, (0 << 4) | 1,
            Opcode.HALT
        });
        p2.first().execute(p2.second());
        assertEquals(4, p2.second().registers().get(RegisterFile.R0));
    }

    @Test
    void shiftCountTooLargeThrows() {
        // SHL with count 16
        byte[] code = {
            Opcode.LI, 0, 0x00, 0x01,
            Opcode.LI, 1, 0x00, 0x10,
            Opcode.SHL, (0 << 4) | 1,
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);

        VmException e = assertThrows(VmException.class, () -> p.first().execute(p.second()));
        assertEquals(VmException.Code.INVALID_SHIFT_COUNT, e.code());
    }

    @Test
    void cmpAndCmpu() {
        // CMP signed: 10 vs 20 -> negative
        var p = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, 0x0A,
            Opcode.LI, 1, 0x00, 0x14,
            Opcode.CMP, (0 << 4) | 1,
            Opcode.HALT
        });
        p.first().execute(p.second());
        assertTrue(p.second().registers().getN());
        assertFalse(p.second().registers().getZ());

        // CMPU unsigned: 10 vs 20 -> carry
        var p2 = createInterpreterAndContext(new byte[]{
            Opcode.LI, 0, 0x00, 0x0A,
            Opcode.LI, 1, 0x00, 0x14,
            Opcode.CMPU, (0 << 4) | 1,
            Opcode.HALT
        });
        p2.first().execute(p2.second());
        assertTrue(p2.second().registers().getC());
    }

    @Test
    void jumpInstructions() {
        // JMP to HALT (skip LI)
        // offset = +4 (skip LI R0, 0x1234)
        byte[] code = {
            Opcode.JMP, 0x00, 0x04,
            Opcode.LI, 0, 0x12, 0x34,
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());
        assertEquals(0, p.second().registers().get(RegisterFile.R0));

        // JZ taken
        byte[] code2 = {
            Opcode.LI, 0, 0x00, 0x00,
            Opcode.JZ, 0x00, 0x04, // jump to HALT (skip 4-byte LI)
            Opcode.LI, 0, 0x12, 0x34,
            Opcode.HALT
        };
        var p2 = createInterpreterAndContext(code2);
        p2.first().execute(p2.second());
        assertEquals(0, p2.second().registers().get(RegisterFile.R0));

        // JZ not taken
        byte[] code3 = {
            Opcode.LI, 0, 0x00, 0x01,
            Opcode.JZ, 0x00, 0x03,
            Opcode.LI, 0, 0x12, 0x34,
            Opcode.HALT
        };
        var p3 = createInterpreterAndContext(code3);
        p3.first().execute(p3.second());
        assertEquals(0x1234, p3.second().registers().get(RegisterFile.R0));
    }

    @Test
    void callAndRet() {
        // CALL to subroutine that returns
        // Layout:
        // 0: LI R0, 0
        // 4: CALL +1 (to subroutine at IP=8)
        // 7: HALT (after return)
        // 8: LI R0, 0x1234 (subroutine)
        // 12: RET
        // 13: HALT
        byte[] code = {
            Opcode.LI, 0, 0x00, 0x00,
            Opcode.CALL, 0x00, 0x01, // call offset +1 (to IP=8)
            Opcode.HALT, // after return
            Opcode.LI, 0, 0x12, 0x34, // subroutine
            Opcode.RET,
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());
        // Should have executed LI in subroutine and returned
        // After HALT, R0 should be 0x1234
        assertEquals(0x1234, p.second().registers().get(RegisterFile.R0));
        // RET must restore the caller's frame pointer and stack pointer
        assertEquals(0x7000, p.second().registers().get(RegisterFile.FP));
        assertEquals(0x7000, p.second().registers().get(RegisterFile.SP));
    }

    @Test
    void nestedCallRetRestoresFramePointerChain() {
        // main -> sub1 -> sub2, each returning; FP must unwind back to 0x7000.
        // Layout (byte offsets), with RET as a 1-byte opcode:
        //  0: CALL sub1   (target 4)
        //  3: HALT
        //  4: CALL sub2   (target 8)
        //  7: RET
        //  8: LI R0, 0xABCD
        // 12: RET
        // 13: HALT
        byte[] code = {
            Opcode.CALL, 0x00, 0x01,                 // 0: call -> 4
            Opcode.HALT,                             // 3
            Opcode.CALL, 0x00, 0x01,                 // 4: call -> 8
            Opcode.RET,                              // 7
            Opcode.LI, 0, (byte) 0xAB, (byte) 0xCD,  // 8: LI R0, 0xABCD
            Opcode.RET,                              // 12
            Opcode.HALT                              // 13
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());
        assertEquals(0xABCD, p.second().registers().get(RegisterFile.R0));
        assertEquals(0x7000, p.second().registers().get(RegisterFile.FP),
            "FP must unwind through both frames back to the main frame");
        assertEquals(0x7000, p.second().registers().get(RegisterFile.SP));
    }

    @Test
    void haltStopsExecution() {
        byte[] code = {
            Opcode.HALT,
            Opcode.LI, 0, 0x12, 0x34,
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());
        assertEquals(0, p.second().registers().get(RegisterFile.R0));
    }

    @Test
    void invalidOpcodeThrows() {
        byte[] code = {b(0xFF), Opcode.HALT};
        var p = createInterpreterAndContext(code);

        VmException e = assertThrows(VmException.class, () -> p.first().execute(p.second()));
        assertEquals(VmException.Code.INVALID_OPCODE, e.code());
    }

    @Test
    void outOfFuelThrows() {
        // Infinite JMP loop: JMP to self (offset = -3)
        // JMP is 3 bytes at IP=0. To jump back to IP=0, offset = -3 (0xFFFD)
        byte[] code = {
            Opcode.JMP, (byte)0xFF, (byte)0xFD, // JMP -3 (infinite loop)
            Opcode.HALT
        };

        // Use low fuel budget (100) to trigger OUT_OF_FUEL quickly
        SoftMMU mm = createSoftMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        VmInterpreter interpreter = createInterpreter(code, mm, hsr);
        ExecutionContext ctx = createContext(mm, hsr).fuelBudget(100);
        try {
            interpreter.execute(ctx);
            fail("Expected OUT_OF_FUEL exception");
        } catch (VmException e) {
            assertEquals(VmException.Code.OUT_OF_FUEL, e.code());
        }
    }

    @Test
    void memoryReadWrite() {
        SoftMMU mm = createSoftMMU();
        // Write to the data region (0x3000)
        int vaddr = 0x3000;
        mm.writeWord(vaddr, 0x0A);

        byte[] code = {
            Opcode.LDW, 0, 0x30, 0x00, // LDW R0, [0x3000]
            Opcode.HALT
        };
        // Use the same SoftMMU for both write and interpreter
        HostServiceRegistry hsr = new HostServiceRegistry();
        VmInterpreter interpreter = createInterpreter(code, mm, hsr);
        ExecutionContext ctx = createContext(mm, hsr);
        interpreter.execute(ctx);
        assertEquals(0x0A, ctx.registers().get(RegisterFile.R0));
    }

    @Test
    void trapThrows() {
        byte[] code = {Opcode.TRAP, 0x01, Opcode.HALT};
        var p = createInterpreterAndContext(code);

        VmException e = assertThrows(VmException.class, () -> p.first().execute(p.second()));
        assertEquals(VmException.Code.ARITHMETIC_FAULT, e.code());
    }

    // ===== Indirect memory access (ISA v2: LDBI/LDWI/STBI/STWI) =====

    @Test
    void indirectByteStoreThenLoad() {
        // STBI R2, [R1] then LDBI R3, [R1]; the address comes from a register.
        byte[] code = {
            Opcode.LI, 1, 0x30, 0x00,        // R1 = 0x3000 (data region)
            Opcode.LI, 2, 0x00, 0x41,        // R2 = 0x41 ('A')
            Opcode.STBI, (2 << 4) | 1,       // byte[R1] = R2
            Opcode.LDBI, (3 << 4) | 1,       // R3 = byte[R1]
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(0x41, p.second().registers().get(RegisterFile.R3));
    }

    @Test
    void indirectWordIsLittleEndian() {
        byte[] code = {
            Opcode.LI, 1, 0x30, 0x00,        // R1 = 0x3000
            Opcode.LI, 2, 0x12, 0x34,        // R2 = 0x1234
            Opcode.STWI, (2 << 4) | 1,       // word[R1] = R2 (little-endian)
            Opcode.LDWI, (3 << 4) | 1,       // R3 = word[R1]
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(0x1234, p.second().registers().get(RegisterFile.R3));
    }

    @Test
    void indirectLoadUpdatesZeroFlag() {
        // LDBI must update Z/N so conditional jumps work on the loaded value.
        // R2 is 0, so byte[R1] = 0 and the following JZ must be taken.
        byte[] code = {
            Opcode.LI, 1, 0x30, 0x00,        // R1 = 0x3000
            Opcode.STBI, (2 << 4) | 1,       // byte[R1] = R2 (= 0)
            Opcode.LDBI, (3 << 4) | 1,       // R3 = byte[R1] = 0 -> sets Z
            Opcode.JZ, 0x00, 0x04,           // if Z, skip the LI R4 below
            Opcode.LI, 4, b(0xFF), b(0xFF),  // R4 = 0xFFFF (must be skipped)
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        p.first().execute(p.second());

        assertEquals(0, p.second().registers().get(RegisterFile.R3));
        assertEquals(0, p.second().registers().get(RegisterFile.R4),
            "JZ after a zero LDBI must skip the next instruction");
    }

    @Test
    void indirectAccessToUnmappedAddressFaults() {
        // The address still passes through the SoftMMU, so protection applies.
        byte[] code = {
            Opcode.LI, 1, b(0xF0), 0x00,     // R1 = 0xF000 (unmapped)
            Opcode.LDBI, (3 << 4) | 1,       // R3 = byte[R1]
            Opcode.HALT
        };
        var p = createInterpreterAndContext(code);
        assertThrows(VmException.class, () -> p.first().execute(p.second()));
    }

    // ===== HOST service dispatch through the HostContext port (IFLOW-AUD-016) =====

    @Test
    void hostPropertyReadUsesHostContext() {
        SoftMMU mm = createSoftMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        hsr.declareCapability("property.read");
        ExecutionContext ctx = createContext(mm, hsr)
            .allowedCapabilities(new String[]{"property.read"});
        ctx.hostContext(new me.majhrs16.suite.iflow.vm.service.HostContext() {
            @Override public String readProperty(int scope, int propId) { return "xyz"; }
        });

        byte[] code = {
            Opcode.LI, 0, 0x01, 0x00,          // R0 = PROPERTY_READ
            Opcode.LI, 1, 0x01, 0x10,          // options = (SCOPE_MESSAGE<<8)|PROP_CONTENT
            Opcode.LI, 2, b(0x80), 0x00,       // base = output buffer
            Opcode.LI, 3, 0x00, b(0xFF),       // length
            Opcode.HOST, 0x00,
            Opcode.LDB, 0, b(0x80), 0x00,      // R0 = first byte the host wrote
            Opcode.HALT
        };
        new VmInterpreter(ProgramImage.builder().entryPoint(0).code(code).build(), mm, hsr)
            .execute(ctx);

        assertEquals(0x78, ctx.registers().get(RegisterFile.R0)); // 'x'
    }
}