package me.majhrs16.suite.iflow.vm;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

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
        // LI R1, DECISION_CONTINUE (HOST ABI v2: R1 = options)
        assertEquals(Opcode.LI, code[4] & 0xFF);
        assertEquals(1, code[5] & 0xFF);
        assertEquals((HostServiceRegistry.DECISION_CONTINUE >> 8) & 0xFF, code[6] & 0xFF);
        assertEquals(HostServiceRegistry.DECISION_CONTINUE & 0xFF, code[7] & 0xFF);
        // HOST (opcode + reserved operand byte)
        assertEquals(Opcode.HOST, code[8] & 0xFF);
        assertEquals(0, code[9] & 0xFF);
        // HALT
        assertEquals(Opcode.HALT, code[10] & 0xFF);
    }

    @Test
    void loadProgramParsesAValidPolicyFlow(@TempDir Path dir) throws IOException {
        byte[] code = { Opcode.HALT };
        Files.write(dir.resolve("policy.flow"), buildFlow(code, 0, "routing.decide"));

        ProgramImage program = VmConfigLoader.loadProgram(dir, null);

        // The real .flow is used, not the 11-byte pass-through fallback.
        assertArrayEquals(code, program.code());
        assertEquals(0, program.entryPoint());
        assertTrue(program.hasCapability("routing.decide"));
    }

    @Test
    void loadProgramFallsBackWhenPolicyFlowIsCorrupt(@TempDir Path dir) throws IOException {
        byte[] corrupt = buildFlow(new byte[]{ Opcode.HALT }, 0);
        corrupt[corrupt.length - 1] ^= 0xFF; // break the SHA-256 trailer
        Files.write(dir.resolve("policy.flow"), corrupt);

        ProgramImage program = VmConfigLoader.loadProgram(dir, null);

        // Malformed binaries must not abort startup: pass-through is used.
        assertEquals(11, program.code().length);
    }

    /**
     * Serializes a minimal valid .flow image: format-2 header (magic + versions +
     * sizes + debug length) + code (no constants, no debug) + capabilities +
     * SHA-256 trailer, matching {@code ProgramImageParser.parse}.
     */
    private static byte[] buildFlow(byte[] code, int entryPoint, String... capabilities) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{0x46, 0x4C, 0x4F, 0x57}); // magic "FLOW"
            out.write(2); // format version
            out.write(2); // ISA version
            out.write(2); // minimum ABI version
            out.write(1); // minimum firmware version
            out.write((entryPoint >> 8) & 0xFF);
            out.write(entryPoint & 0xFF);
            out.write((code.length >> 8) & 0xFF);
            out.write(code.length & 0xFF);
            out.write(0); // constants length (high)
            out.write(0); // constants length (low)
            out.write(capabilities.length);
            out.write(0); // reserved
            out.write(0); // debug length (high)
            out.write(0); // debug length (low)
            out.write(0); // reserved
            out.write(0); // reserved
            out.write(code);
            for (String cap : capabilities) {
                out.write(cap.getBytes(StandardCharsets.US_ASCII));
                out.write(0);
            }
            byte[] body = out.toByteArray();
            out.write(MessageDigest.getInstance("SHA-256").digest(body));
            return out.toByteArray();
        } catch (Exception e) {
            throw new AssertionError("failed to build test fixture", e);
        }
    }
}