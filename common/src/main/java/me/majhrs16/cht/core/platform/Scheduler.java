package me.majhrs16.cht.core.platform;

import java.util.concurrent.TimeUnit;

/**
 * Port over the platform threading model.
 *
 * <p>The core engine never touches server threads directly; everything
 * asynchronous goes through this interface so both Spigot (Bukkit scheduler)
 * and Fabric (synchronized server ticks + its own executor) can provide a
 * compatible implementation.</p>
 */
public interface Scheduler {

    /**
     * Runs a task on the main server thread as soon as possible.
     *
     * @param task the task to run.
     */
    void runOnMainThread(Runnable task);

    /**
     * Runs a task asynchronously as soon as possible.
     *
     * @param task the task to run.
     */
    void runAsync(Runnable task);

    /**
     * Runs a task asynchronously after a delay.
     *
     * @param task  the task to run.
     * @param delay delay before execution.
     * @param unit  time unit of {@code delay}.
     */
    void runAsyncLater(Runnable task, long delay, TimeUnit unit);

    /**
     * Runs a task asynchronously on a fixed period.
     *
     * @param task   the task to run.
     * @param delay  initial delay.
     * @param period execution period.
     * @param unit   time unit of both values.
     */
    void runAsyncRepeating(Runnable task, long delay, long period, TimeUnit unit);
}