package me.majhrs16.cht.core.platform;

import me.majhrs16.cht.core.player.Subject;

/**
 * Port for resolving external placeholders inside templates.
 *
 * <p>On Spigot this is backed by PlaceholderAPI; on Fabric there is no
 * equivalent ecosystem, so the implementation may fall back to returning the
 * input unchanged. The template engine only guarantees that the returned text
 * is safe to embed as literal content (no MiniMessage tag interpretation).</p>
 */
public interface PlaceholderResolver {

    /**
     * Resolves every supported external placeholder within {@code input} for
     * the given subject.
     *
     * @param subject the context subject (may be the console).
     * @param input   the raw template fragment.
     * @return the input with known placeholders replaced.
     */
    String resolve(Subject subject, String input);

    /**
     * True if the platform provides any external placeholder support.
     */
    boolean available();
}
