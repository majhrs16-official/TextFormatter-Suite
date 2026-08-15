package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.platform.Scheduler;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fabric {@link Scheduler}. Main-thread work goes through the MinecraftServer
 * executor; async work uses a dedicated scheduled thread pool so network calls
 * never block the server thread.
 */
final class FabricScheduler implements Scheduler {

    private final MinecraftServer server;
    private final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ChatTranslator-async");
            thread.setDaemon(true);
            return thread;
        });

    FabricScheduler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void runOnMainThread(Runnable task) {
        if (server.isOnThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        CompletableFuture.runAsync(task, executor);
    }

    @Override
    public void runAsyncLater(Runnable task, long delay, TimeUnit unit) {
        executor.schedule(task, delay, unit);
    }

    @Override
    public void runAsyncRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        executor.scheduleAtFixedRate(task, delay, period, unit);
    }

    void shutdown() {
        executor.shutdownNow();
    }
}