package me.majhrs16.suite.api.runtime;

import me.majhrs16.suite.api.ModuleDescriptor;

/**
 * Lifecycle entry point of a suite module.
 *
 * <p>This contract is intentionally separate from
 * {@link me.majhrs16.suite.api.Module}: {@code Module} only describes the module
 * for the dependency graph, while {@code ModuleActivator} is what the
 * {@code ModuleHost} instantiates to start and stop it.</p>
 *
 * <p>A module JAR declares its activator through
 * {@code META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator}.
 * The runtime discovers it with a classloader scoped to that JAR, calls
 * {@link #start(ModuleContext)}, and later calls {@link #stop()} before closing
 * the classloader.</p>
 *
 * <p>Implementations must be safe to construct without arguments and must not
 * keep static state that survives a reload. {@link #stop()} must be idempotent
 * and must release everything that {@link #start(ModuleContext)} acquired,
 * either directly or through the context's {@link ResourceTracker}.</p>
 */
public interface ModuleActivator {

    /**
     * @return the immutable descriptor of this module, validated by the host
     *         against the package manifest.
     */
    ModuleDescriptor descriptor();

    /**
     * Starts the module.
     *
     * <p>Services must be registered through
     * {@link ModuleContext#services()} so the runtime can remove them when the
     * module stops. Background work must go through
     * {@link ModuleContext#scheduler()} and resources through
     * {@link ModuleContext#resources()}.</p>
     *
     * @param context module-scoped runtime services
     * @throws Exception if the module cannot start; the runtime rolls back the
     *                   activation and marks the module {@link ModuleState#FAILED}
     */
    void start(ModuleContext context) throws Exception;

    /**
     * Stops the module and releases its resources.
     *
     * @throws Exception if cleanup fails; the runtime still closes the module
     *                   scope and may mark the module
     *                   {@link ModuleState#RESTART_REQUIRED}
     */
    void stop() throws Exception;
}
