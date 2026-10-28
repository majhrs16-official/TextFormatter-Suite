package me.majhrs16.suite.api.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Registry of live services offered by active modules.
 *
 * <p>{@code ServiceLoader} only discovers what is already on the classpath and
 * cannot unregister anything. The runtime therefore uses this mutable registry
 * for services that appear and disappear with module activation. Every
 * registration is associated with the module that created it and is closed when
 * that module stops.</p>
 *
 * <p>Lookups are exact-type lookups: {@link #find(Class)} returns the first
 * service registered under the requested type, and {@link #findAll(Class)}
 * returns all of them in registration order.</p>
 */
public interface ServiceRegistry {

    /**
     * Registers a service under {@code type}.
     *
     * @param type    the contract clients will look up
     * @param service the implementation
     * @param <T>     service type
     * @return a handle whose {@link Registration#close()} unregisters the
     *         service; closing twice is a no-op
     */
    <T> Registration register(Class<T> type, T service);

    /**
     * @return the first service registered under {@code type}, if any
     */
    <T> Optional<T> find(Class<T> type);

    /**
     * @return every service registered under {@code type}, in registration
     *         order; never {@code null}
     */
    <T> List<T> findAll(Class<T> type);

    /** Handle returned by {@link #register(Class, Object)}. */
    interface Registration extends AutoCloseable {

        /** Unregisters the service. Idempotent. */
        void close();
    }
}
