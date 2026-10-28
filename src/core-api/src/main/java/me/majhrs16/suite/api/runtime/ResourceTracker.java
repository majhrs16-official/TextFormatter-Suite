package me.majhrs16.suite.api.runtime;

/**
 * Tracks resources acquired by a module so they can be released on shutdown.
 *
 * <p>Hot unload is only real when every resource is released: executors,
 * sockets, file watchers, classloader references and platform listeners. The
 * tracker gives modules a single place to register cleanup actions and gives
 * the runtime a deterministic order to run them.</p>
 *
 * <p>Cleanups run in reverse registration order (last acquired, first
 * released). A failing cleanup must not prevent the remaining cleanups from
 * running; the runtime reports the failure and continues.</p>
 */
public interface ResourceTracker extends AutoCloseable {

    /**
     * Tracks an {@link AutoCloseable} so it is closed with the module.
     *
     * @param resource resource to close
     * @param <T>      resource type
     * @return the same resource, for chaining
     */
    <T extends AutoCloseable> T track(T resource);

    /**
     * Registers an arbitrary cleanup action.
     *
     * @param cleanup action to run on release
     * @return a handle that removes the action without running it
     */
    Registration onRelease(Runnable cleanup);

    /** Releases every tracked resource and action. Idempotent. */
    void releaseAll();

    /** @return {@code true} once {@link #releaseAll()} has run. */
    boolean isReleased();

    @Override
    default void close() {
        releaseAll();
    }

    /** Handle returned by {@link #onRelease(Runnable)}. */
    interface Registration extends AutoCloseable {

        /** Removes the cleanup action without running it. Idempotent. */
        void close();
    }
}
