package me.majhrs16.suite.iflow.vm.rom;

/**
 * The artifact claims to be a v3 ROM but violates a structural invariant,
 * a limit, a hash, or a type-specific rule.
 */
public final class InvalidRomException extends RomException {

    public InvalidRomException(String message) {
        super(message);
    }
}
