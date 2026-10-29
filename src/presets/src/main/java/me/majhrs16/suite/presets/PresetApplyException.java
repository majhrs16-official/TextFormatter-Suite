package me.majhrs16.suite.presets;

/**
 * Raised when a preset cannot be persisted through a {@link PresetTarget}.
 *
 * <p>The target must fail explicitly: a preset that cannot be written must not
 * be reported as applied.</p>
 */
public final class PresetApplyException extends RuntimeException {

    public PresetApplyException(String message) {
        super(message);
    }

    public PresetApplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
