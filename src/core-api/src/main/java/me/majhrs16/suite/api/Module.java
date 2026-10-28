package me.majhrs16.suite.api;

/**
 * SPI entry point for a suite module descriptor.
 *
 * <p>Every module jar registers an implementation of this interface through
 * {@code META-INF/services/me.majhrs16.suite.api.Module}; the runtime host
 * discovers them with {@link java.util.ServiceLoader}. Exposing a
 * {@link ModuleDescriptor} is enough for the dependency graph.</p>
 *
 * <p>The lifecycle is a separate contract:
 * {@link me.majhrs16.suite.api.runtime.ModuleActivator} declares
 * {@code start(ModuleContext)} and {@code stop()}. A module that participates
 * in hot activation implements both interfaces.</p>
 */
public interface Module {

    /**
     * @return the immutable descriptor of this module.
     */
    ModuleDescriptor descriptor();
}