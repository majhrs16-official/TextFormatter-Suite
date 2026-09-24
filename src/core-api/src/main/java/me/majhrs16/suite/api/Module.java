package me.majhrs16.suite.api;

/**
 * SPI entry point for a suite module.
 *
 * <p>Every module jar registers an implementation of this interface through
 * {@code META-INF/services/me.majhrs16.suite.api.Module}; the runtime host
 * discovers them with {@link java.util.ServiceLoader}. There is no lifecycle
 * contract and no reflective method cracking: exposing a {@link ModuleDescriptor}
 * is enough for the dependency graph. Modules activate whatever services they
 * provide (translator, formatter, router...) through their own platform entry
 * point (Spigot plugin / Fabric mod).</p>
 */
public interface Module {

    /**
     * @return the immutable descriptor of this module.
     */
    ModuleDescriptor descriptor();
}