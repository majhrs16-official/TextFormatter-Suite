package me.majhrs16.suite.api.runtime;

/**
 * Module-scoped scheduler for delayed and repeating work.
 *
 * <p>Every task created through this interface belongs to the module that
 * created it. When the module stops, the runtime cancels all of its tasks;
 * modules must still cancel long-lived tasks explicitly through the returned
 * handle or the {@link ResourceTracker}.</p>
 *
 * <p>Implementations may run tasks on a platform scheduler (Bukkit/Fabric) or
 * on their own executor, but must never leak threads past module shutdown.</p>
 */
public interface Scheduler {

    /**
     * Runs {@code task} once after {@code delayMillis}.
     *
     * @return a handle used to cancel the task
     */
    Cancellable schedule(Runnable task, long delayMillis);

    /**
     * Runs {@code task} repeatedly.
     *
     * @param initialDelayMillis delay before the first execution
     * @param periodMillis       period between executions
     * @return a handle used to cancel the task
     */
    Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long periodMillis);

    /**
     * Runs {@code task} as soon as possible on the scheduler's execution
     * context.
     *
     * @return a handle used to cancel the task if it has not started yet
     */
    Cancellable execute(Runnable task);

    /** Handle returned by the scheduling methods. */
    interface Cancellable extends AutoCloseable {

        /** Cancels the task. Idempotent. */
        void cancel();

        /** @return {@code true} once the task has been cancelled. */
        boolean isCancelled();

        @Override
        default void close() {
            cancel();
        }
    }
}
