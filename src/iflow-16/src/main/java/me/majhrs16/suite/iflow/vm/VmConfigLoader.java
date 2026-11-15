package me.majhrs16.suite.iflow.vm;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.isa.ProgramImageParser;
import me.majhrs16.suite.iflow.vm.isa.VmException;

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
     *
     * <p>A missing {@code policy.flow} selects the documented pass-through
     * program. A file that exists but cannot be read or parsed is a configuration
     * error: it propagates instead of silently replacing the policy with
     * pass-through. The two states must not be confused.</p>
     */
    public static ProgramImage loadProgram(Path configDir, PluginLogger logger) {
        Path nativeProg = configDir.resolve("policy.flow");
        if (Files.exists(nativeProg)) {
            try {
                return loadNativeProgram(nativeProg, logger);
            } catch (IOException failure) {
                throw new VmException(VmException.Code.INVALID_PROGRAM,
                    "Cannot read policy.flow: " + failure.getMessage(), failure);
            }
        }

        // No policy configured: the documented pass-through program is the
        // explicit default, not a recovery path for a broken file.
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