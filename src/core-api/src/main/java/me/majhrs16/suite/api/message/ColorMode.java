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

    FORCE,
}