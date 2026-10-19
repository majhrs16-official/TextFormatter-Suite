package me.majhrs16.suite.api.message;

/**
 * How the color of the message content should be applied.
 *
 * <ul>
 *   <li>{@link #DISABLE} — strip any color codes from the content.</li>
 *   <li>{@link #BY_PERMISSION} — keep color only for players holding the
 *       color permission.</li>
 *   <li>{@link #FORCE} — always keep the color codes.</li>
 * </ul>
 */
public enum ColorMode {

    DISABLE,

    BY_PERMISSION,

    FORCE;

    /**
     * Parses a colour-mode name (case-insensitive, surrounding blanks ignored).
     *
     * @return the matching mode, or empty when {@code raw} is null or unknown
     */
    public static java.util.Optional<ColorMode> of(String raw) {
        if (raw == null) {
            return java.util.Optional.empty();
        }
        String trimmed = raw.trim();
        for (ColorMode mode : values()) {
            if (mode.name().equalsIgnoreCase(trimmed)) {
                return java.util.Optional.of(mode);
            }
        }
        return java.util.Optional.empty();
    }
}