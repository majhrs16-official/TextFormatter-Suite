package me.majhrs16.suite.iflow.vm.rom;

/**
 * The artifact is not a v3 ROM: wrong magic, a v2 {@code .flow}, or a
 * version/field value that this implementation does not speak.
 *
 * <p>An unsupported format is rejected explicitly and is never read through a
 * fallback path.</p>
 */
public final class UnsupportedFormatException extends RomException {

    public UnsupportedFormatException(String message) {
        super(message);
    }
}
