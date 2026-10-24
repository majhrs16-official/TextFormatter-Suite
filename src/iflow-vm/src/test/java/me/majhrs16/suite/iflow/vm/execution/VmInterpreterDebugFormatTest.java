package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the compact register/flag dump used by the {@code VM_CYCLE} debug
 * line. After the module formatter was removed, the dump is built at the call
 * site (lazily, behind the debug guard) by {@link VmInterpreter#formatRegisters}.
 */
class VmInterpreterDebugFormatTest {

    @Test
    void dumpIncludesEveryRegisterInOrder() {
        RegisterFile regs = new RegisterFile();
        regs.set(RegisterFile.R1, 0xABCD);
        regs.set(RegisterFile.IP, 0x0100);
        regs.set(RegisterFile.SP, 0x0F00);
        regs.set(RegisterFile.FP, 0x0E00);

        String out = VmInterpreter.formatRegisters(regs);

        assertTrue(out.startsWith("R0=0x0000 R1=0xABCD"), out);
        assertTrue(out.contains("IP=0x0100"), out);
        assertTrue(out.contains("SP=0x0F00"), out);
        assertTrue(out.contains("FP=0x0E00"), out);
        assertTrue(out.contains("FL=0x0000"), out);
    }

    @Test
    void dumpIncludesAllFourFlags() {
        RegisterFile regs = new RegisterFile();
        regs.setZ(true);
        regs.setN(true);

        String out = VmInterpreter.formatRegisters(regs);

        assertTrue(out.endsWith("(Z=true N=true C=false V=false)"), out);
    }
}
