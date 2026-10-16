package me.majhrs16.suite.iflow.vm;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;

class VmConfigLoaderTest {

    @Test
    void loadProgramWithoutConfigReturnsPassThrough() {
        Path nonExistentDir = Paths.get("/tmp/nonexistent_" + System.currentTimeMillis());
        ProgramImage program = VmConfigLoader.loadProgram(nonExistentDir, null);
        assertNotNull(program);
        assertEquals(0, program.entryPoint());
        assertEquals(11, program.code().length);
        assertTrue(program.hasCapability("routing.decide"));
    }

    @Test
    void loadProgramWithNullLogger() {
        Path nonExistentDir = Paths.get("/tmp/nonexistent_" + System.currentTimeMillis());
        ProgramImage program = VmConfigLoader.loadProgram(nonExistentDir, null);
        assertNotNull(program);
    }

    @Test
    void loadProgramWithLogger() {
        Path nonExistentDir = Paths.get("/tmp/nonexistent_" + System.currentTimeMillis());
        PluginLogger logger = new PluginLogger() {
            @Override public void info(String message, Object... args) {}
            @Override public void warn(String message, Object... args) {}
            @Override public void error(String message, Object... args) {}
            @Override public void error(String message, Throwable throwable) {}
            @Override public void debug(String message, Object... args) {}
        };
        ProgramImage program = VmConfigLoader.loadProgram(nonExistentDir, logger);
        assertNotNull(program);
    }

    @Test
    void passThroughProgramHasCorrectBytecode() {
        ProgramImage program = VmConfigLoader.loadProgram(Paths.get("/nonexistent"), null);
        byte[] code = program.code();
        // LI R0, ROUTE_DECISION
        assertEquals(Opcode.LI, code[0] & 0xFF);
        assertEquals(0, code[1] & 0xFF);
        assertEquals((HostServiceRegistry.ROUTE_DECISION >> 8) & 0xFF, code[2] & 0xFF);
        assertEquals(HostServiceRegistry.ROUTE_DECISION & 0xFF, code[3] & 0xFF);
        // LI R3, DECISION_CONTINUE
        assertEquals(Opcode.LI, code[4] & 0xFF);
        assertEquals(3, code[5] & 0xFF);
        assertEquals((HostServiceRegistry.DECISION_CONTINUE >> 8) & 0xFF, code[6] & 0xFF);
        assertEquals(HostServiceRegistry.DECISION_CONTINUE & 0xFF, code[7] & 0xFF);
        // HOST (opcode + reserved operand byte)
        assertEquals(Opcode.HOST, code[8] & 0xFF);
        assertEquals(0, code[9] & 0xFF);
        // HALT
        assertEquals(Opcode.HALT, code[10] & 0xFF);
    }
}