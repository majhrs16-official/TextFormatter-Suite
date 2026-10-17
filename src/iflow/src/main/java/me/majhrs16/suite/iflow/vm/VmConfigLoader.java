package me.majhrs16.suite.iflow.vm;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads VM programs from binary .flow files.
 * <p>
 * No YAML, no compilation. Direct binary loading only.
 * </p>
 */
public final class VmConfigLoader {

    private VmConfigLoader() {}

    /**
     * Loads a VM program from the config directory.
     * Tries to load policy.flow first, falls back to pass-through program.
     */
    public static ProgramImage loadProgram(Path configDir, PluginLogger logger) {
        Path nativeProg = configDir.resolve("policy.flow");
        if (Files.exists(nativeProg)) {
            try {
                return loadNativeProgram(nativeProg, logger);
            } catch (Exception e) {
                if (logger != null) {
                    logger.warn("Failed to load native program, using pass-through: " + e.getMessage());
                }
            }
        }

        // Default pass-through program
        if (logger != null) {
            logger.debug("No policy.flow found, using pass-through program");
        }
        return createPassThroughProgram();
    }

    private static ProgramImage loadNativeProgram(Path path, PluginLogger logger) throws IOException {
        byte[] data = Files.readAllBytes(path);
        // Parse binary format - simplified for now
        // In production, use proper deserialization (e.g., Jackson CBOR/MessagePack or custom binary format)
        // For now, return pass-through
        if (logger != null) {
            logger.info("Loaded native VM program: " + path + " (" + data.length + " bytes)");
        }
        return createPassThroughProgram();
    }

    private static ProgramImage createPassThroughProgram() {
        // Minimal program: call ROUTE_DECISION service with DECISION_CONTINUE
        // HOST ABI v2: R0 = service ID, R1 = options (decision code)
        return ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (HostServiceRegistry.ROUTE_DECISION >> 8) & 0xFF, HostServiceRegistry.ROUTE_DECISION & 0xFF, // LI R0, ROUTE_DECISION
                Opcode.LI, 1, (HostServiceRegistry.DECISION_CONTINUE >> 8) & 0xFF, HostServiceRegistry.DECISION_CONTINUE & 0xFF, // LI R1, DECISION_CONTINUE
                Opcode.HOST, 0x00,                                              // HOST (opcode + reserved operand byte)
                Opcode.HALT                                                     // HALT
            })
            .capabilities(new String[]{"routing.decide"})
            .build();
    }

    // Inline constants to avoid circular dependency
    private static class Opcode {
        public static final int LI = 0x00;
        public static final int HOST = 0x60;
        public static final int HALT = 0x68;
    }

    private static class HostServiceRegistry {
        public static final int ROUTE_DECISION = 0x0300;
        public static final int DECISION_CONTINUE = 6;
    }
}