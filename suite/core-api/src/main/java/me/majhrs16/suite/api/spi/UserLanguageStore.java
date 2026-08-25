package me.majhrs16.suite.api.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistent per-user language preference, the functional equivalent of the
 * original project's {@code /cht lang} storage (uuid → language).
 *
 * <p>Values are backend-agnostic strings:</p>
 * <ul>
 *   <li>{@link #AUTO} — follow the client locale.</li>
 *   <li>{@link #OFF} — translation disabled for this user (as sender their
 *       messages stay literal; as receiver they get source text).</li>
 *   <li>any other non-blank value — an ISO-ish language code
 *       ({@code "es"}, {@code "zh-CN"}).</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe: lookups happen on async chat
 * threads. Backends decide persistence timing (immediate write vs buffered
 * {@link #flush()}).</p>
 */
public interface UserLanguageStore {

    /** Stored value meaning "use the client locale". */
    String AUTO = "auto";

    /** Stored value meaning "translation disabled for this user". */
    String OFF = "off";

    /** @return the stored raw value, empty when the user never chose one. */
    Optional<String> languageOf(UUID uuid);

    /** Persists the raw value ({@link #AUTO}, {@link #OFF} or a code). */
    void save(UUID uuid, String value);

    /**
     * Forces buffered backends to write through. Immediate-write backends
     * may ignore it.
     */
    default void flush() {
    }
}
