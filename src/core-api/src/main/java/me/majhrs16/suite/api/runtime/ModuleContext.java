package me.majhrs16.suite.api.runtime;

import java.nio.file.Path;

import me.majhrs16.suite.api.command.CommandRegistry;
import me.majhrs16.suite.api.config.ConfigRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Module-scoped view of the runtime.
 *
 * <p>A context belongs to exactly one module and to one activation attempt. It
 * never exposes Bukkit, Fabric or any other platform type; modules interact
 * only with the neutral contracts in this package.</p>
 *
 * <p>Every registry, subscription, task and resource obtained through the
 * context is owned by the module. The runtime closes the context after
 * {@link ModuleActivator#stop()}, which must leave no services, listeners,
 * tasks, threads, sockets or classloader references behind.</p>
 */
public interface ModuleContext extends AutoCloseable {

    /** @return the module id declared by its descriptor. */
    String moduleId();

    /**
     * @return the module-private configuration directory, already created by
     *         the runtime; modules must not write outside it
     */
    Path configDir();

    /** @return a logger prefixed with the module id. */
    PluginLogger logger();

    /** @return the service registry scoped to this module. */
    ServiceRegistry services();

    /** @return the event bus scoped to this module. */
    EventBus events();

    /** @return the scheduler scoped to this module. */
    Scheduler scheduler();

    /** @return the resource tracker scoped to this module. */
    ResourceTracker resources();

    /**
     * @return the command registry scoped to this module; the module must root
     *         its subtree at {@link #moduleId()} and every registration is
     *         removed when the context closes
     * @throws UnsupportedOperationException when the context does not provide
     *         a command surface (test doubles for unrelated contracts)
     */
    default CommandRegistry commands() {
        throw new UnsupportedOperationException("command registry is not available in this context");
    }

    /**
     * @return the configuration registry scoped to this module; the section
     *         namespace must equal {@link #moduleId()} and every section is
     *         removed when the context closes
     * @throws UnsupportedOperationException when the context does not provide
     *         a configuration surface (test doubles for unrelated contracts)
     */
    default ConfigRegistry config() {
        throw new UnsupportedOperationException("configuration registry is not available in this context");
    }

    /** @return neutral information about the hosting platform. */
    PlatformInfo platform();

    /**
     * Releases the module scope. Called by the runtime, not by the module.
     */
    @Override
    void close();
}
