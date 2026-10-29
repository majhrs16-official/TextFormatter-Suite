package me.majhrs16.suite.api.config;

import java.util.List;
import java.util.Optional;

/**
 * Neutral registry of module configuration sections.
 *
 * <p>Registrations are lifecycle-bound. A module registers its section in
 * {@code start()} and the runtime removes it when the module stops. The
 * registry never deletes persisted values; it only removes the live surface.</p>
 */
public interface ConfigRegistry {

    /**
     * Registers a section.
     *
     * @param section module-owned section
     * @return a handle that removes it
     * @throws ConfigConflictException when the namespace is already registered
     */
    ConfigRegistration register(ConfigSection section);

    /** @return the section registered under {@code namespace}, if any. */
    Optional<ConfigSection> find(String namespace);

    /** @return every registered section in registration order. */
    List<ConfigSection> sections();
}
