package me.majhrs16.suite.api.runtime;

/**
 * Opaque bridge to the native object owned by the platform adapter.
 *
 * <p>Only platform-specific packages (for example {@code sync-bukkit} or
 * {@code sync-fabric}) may resolve this service. Core and feature packages must
 * never inspect the native handle: they communicate through the neutral
 * contracts in this API.</p>
 *
 * <p>The adapter registers one handle before starting the runtime. The handle
 * is removed when the runtime stops, so a package that needs the native plugin
 * or server object must resolve it during {@link ModuleActivator#start}.</p>
 */
@FunctionalInterface
public interface PlatformHandle {

    /** @return the native adapter object, never {@code null}. */
    Object nativeHandle();

    /**
     * Casts the native handle to the requested platform type.
     *
     * @param type expected platform type
     * @param <T>  platform type
     * @return the handle cast to {@code type}
     * @throws ClassCastException when the adapter exposes another type
     */
    default <T> T unwrap(Class<T> type) {
        return type.cast(nativeHandle());
    }
}
