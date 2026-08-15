package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.platform.Scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/**
 * Spigot {@link Scheduler} backed by the Bukkit scheduler and a small shared
 * async executor for one-off tasks.
 */
final class SpigotScheduler implements Scheduler {

    private final JavaPlugin plugin;

    SpigotScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runAsyncLater(Runnable task, long delay, TimeUnit unit) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(
            plugin, task, ticks(delay, unit));
    }

    @Override
    public void runAsyncRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, task, ticks(delay, unit), ticks(period, unit));
    }

    private static long ticks(long amount, TimeUnit unit) {
        // 20 ticks per second; conversion always rounds up to at least 1 tick.
        long ticks = TimeUnit.MILLISECONDS.toSeconds(unit.toMillis(amount)) * 20;
        return Math.max(1, ticks);
    }
}