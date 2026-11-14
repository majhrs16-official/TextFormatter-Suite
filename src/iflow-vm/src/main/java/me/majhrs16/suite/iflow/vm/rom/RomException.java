package me.majhrs16.suite.iflow.vm.rom;

/**
 * Base exception for failures while reading a v3 {@code FLOWROM} image.
 */
public class RomException extends RuntimeException {

    public RomException(String message) {
        super(message);
    }

    public RomException(String message, Throwable cause) {
        super(message, cause);
    }
}
