package me.majhrs16.suite.iflow.vm;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.isa.ProgramImageParser;

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
        return PassThroughProgram.INSTANCE;
    }

    private static ProgramImage loadNativeProgram(Path path, PluginLogger logger) throws IOException {
        byte[] data = Files.readAllBytes(path);
        // Parse the real .flow binary: the parser validates the format/ISA/ABI
        // versions, the section bounds against the memory map, and the SHA-256
        // trailer. A malformed file throws VmException, which loadProgram()
        // catches to fall back to the pass-through program.
        ProgramImage image = ProgramImageParser.parse(data);
        if (logger != null) {
            logger.info("Loaded native VM program: " + path + " (" + data.length + " bytes)");
        }
        return image;
    }
}