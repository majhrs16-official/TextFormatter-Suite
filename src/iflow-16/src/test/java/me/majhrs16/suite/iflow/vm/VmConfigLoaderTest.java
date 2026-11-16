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
     * Serializes a minimal valid ROM2 image: 32-byte header, a 16-byte TOC entry
     * per segment (PROGRAM plus optional CAPABILITIES), payloads and the SHA-256
     * trailer, matching {@code ProgramImageParser.parse}.
     */
    private static byte[] buildFlow(byte[] code, int entryPoint, String... capabilities) {
        try {
            byte[] capabilityBytes = new byte[0];
            if (capabilities.length > 0) {
                ByteArrayOutputStream caps = new ByteArrayOutputStream();
                for (String cap : capabilities) {
                    caps.write(cap.getBytes(StandardCharsets.US_ASCII));
                    caps.write(0);
                }
                capabilityBytes = caps.toByteArray();
            }

            int tocOffset = 32;
            int segmentCount = capabilities.length > 0 ? 2 : 1;
            int payloadStart = tocOffset + segmentCount * 16;
            int capabilityOffset = payloadStart + code.length;
            int payloadSize = capabilityOffset + capabilityBytes.length;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{0x46, 0x4C, 0x4F, 0x57}); // magic "FLOW"
            out.write(2); // format version
            out.write(2); // ISA version
            out.write(2); // minimum ABI version
            out.write(1); // minimum firmware version
            out.write((entryPoint >> 8) & 0xFF);
            out.write(entryPoint & 0xFF);
            out.write((tocOffset >> 8) & 0xFF);
            out.write(tocOffset & 0xFF);
            out.write((segmentCount >> 8) & 0xFF);
            out.write(segmentCount & 0xFF);
            out.write(0); out.write(0); // header flags
            out.write(new byte[]{0x52, 0x4F, 0x4D, 0x32}); // layout "ROM2"
            out.write((payloadSize >>> 24) & 0xFF);
            out.write((payloadSize >>> 16) & 0xFF);
            out.write((payloadSize >>> 8) & 0xFF);
            out.write(payloadSize & 0xFF);
            out.write(new byte[8]); // reserved

            // PROGRAM at 0x0000: a bare-metal image with no BIOS segment.
            out.write(4); // SegmentType.PROGRAM
            out.write(1 | 4 | 8); // R | X | USER
            out.write(0); out.write(0); // virtual address
            out.write((payloadStart >>> 24) & 0xFF);
            out.write((payloadStart >>> 16) & 0xFF);
            out.write((payloadStart >>> 8) & 0xFF);
            out.write(payloadStart & 0xFF);
            out.write((code.length >> 8) & 0xFF);
            out.write(code.length & 0xFF);
            out.write((code.length >> 8) & 0xFF);
            out.write(code.length & 0xFF);
            out.write(new byte[4]); // reserved

            if (capabilities.length > 0) {
                out.write(6); // SegmentType.CAPABILITIES
                out.write(0); // flags
                out.write(0); out.write(0); // virtual address
                out.write((capabilityOffset >>> 24) & 0xFF);
                out.write((capabilityOffset >>> 16) & 0xFF);
                out.write((capabilityOffset >>> 8) & 0xFF);
                out.write(capabilityOffset & 0xFF);
                out.write((capabilityBytes.length >> 8) & 0xFF);
                out.write(capabilityBytes.length & 0xFF);
                out.write(0); out.write(0); // memSize
                out.write(new byte[4]); // reserved
            }

            out.write(code);
            out.write(capabilityBytes);

            byte[] body = out.toByteArray();
            out.write(MessageDigest.getInstance("SHA-256").digest(body));
            return out.toByteArray();
        } catch (Exception e) {
            throw new AssertionError("failed to build test fixture", e);
        }
    }
}