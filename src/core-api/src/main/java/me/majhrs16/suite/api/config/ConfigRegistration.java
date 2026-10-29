package me.majhrs16.suite.api.config;

/**
 * Handle returned by {@link ConfigRegistry#register(ConfigSection)}.
 *
 * <p>Closing it removes the section from the runtime. Closing twice is a
 * no-op. The runtime also removes every section owned by a module when that
 * module stops.</p>
 */
public interface ConfigRegistration extends AutoCloseable {

    /** Removes the section. Idempotent. */
    @Override
    void close();
}
