package me.majhrs16.suite.syncbukkit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import me.majhrs16.suite.synccommon.LocalActionException;
import me.majhrs16.suite.synccommon.LocalEnvironment;
import me.majhrs16.suite.synccommon.LocalEvent;
import me.majhrs16.suite.synccommon.LocalPlayer;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Objective;

/**
 * Bukkit implementation of the neutral {@link LocalEnvironment}.
 *
 * <p>Keeps every Bukkit type inside the {@code sync-bukkit} package. The
 * current event is bound per thread around the dispatch call, so an
 * {@code event.*} action always acts on the event that produced the message
 * being processed.</p>
 */
public final class BukkitLocalEnvironment implements LocalEnvironment {

    private final Plugin plugin;
    private final Server server;
    private final ThreadLocal<Event> bound = new ThreadLocal<>();

    public BukkitLocalEnvironment(Plugin plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }

    /**
     * Binds {@code event} to the current thread while {@code action} runs.
     *
     * @param event  platform event that originated the message
     * @param action dispatch call executed with the binding active
     */
    public void withEvent(Event event, Runnable action) {
        Event previous = bound.get();
        bound.set(event);
        try {
            action.run();
        } finally {
            if (previous == null) {
                bound.remove();
            } else {
                bound.set(previous);
            }
        }
    }

    @Override
    public java.util.List<LocalPlayer> players() {
        return onMainThread(() -> server.getOnlinePlayers().stream()
                .map(this::snapshot)
                .toList());
    }

    @Override
    public Optional<LocalPlayer> player(String reference) {
        return onMainThread(() ->
                Optional.ofNullable(resolve(reference)).map(this::snapshot));
    }

    @Override
    public void sendMessage(String reference, String text) {
        onMainThread(() -> {
            requirePlayer(reference).sendMessage(text);
            return null;
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void kick(String reference, String reason) {
        onMainThread(() -> {
            requirePlayer(reference).kickPlayer(reason);
            return null;
        });
    }

    @Override
    public void runConsoleCommand(String command) {
        onMainThread(() -> {
            boolean handled;
            try {
                handled = server.dispatchCommand(server.getConsoleSender(), command);
            } catch (RuntimeException failure) {
                throw new LocalActionException(
                        LocalActionException.Kind.HOST_FAILURE,
                        "Bukkit rejected the console command: " + describe(failure),
                        failure);
            }
            if (!handled) {
                throw new LocalActionException(
                        LocalActionException.Kind.HOST_FAILURE,
                        "no console command handled '" + command + "'");
            }
            return null;
        });
    }

    @Override
    public void setScore(String objective, String entry, int value) {
        onMainThread(() -> {
            Objective target = server.getScoreboardManager()
                    .getMainScoreboard()
                    .getObjective(objective);
            if (target == null) {
                throw new LocalActionException(
                        LocalActionException.Kind.NOT_FOUND,
                        "scoreboard objective '" + objective + "' does not exist");
            }
            target.getScore(entry).setScore(value);
            return null;
        });
    }

    @Override
    public Optional<LocalEvent> currentEvent() {
        return Optional.ofNullable(bound.get()).map(BukkitLocalEvent::new);
    }

    /**
     * Runs {@code action} on the Bukkit main thread, waiting for completion.
     *
     * <p>Interaction with players and the scoreboard is not thread-safe, and
     * local actions can run on the async chat thread. Waiting keeps the action
     * result honest: no success is reported before the platform executed it.</p>
     */
    private <T> T onMainThread(Supplier<T> action) {
        if (server.isPrimaryThread()) {
            return action.get();
        }
        Future<T> future = server.getScheduler().callSyncMethod(plugin, action::get);
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LocalActionException(
                    LocalActionException.Kind.HOST_FAILURE,
                    "interrupted while waiting for the Bukkit main thread",
                    interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof LocalActionException local) {
                throw local;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new LocalActionException(
                    LocalActionException.Kind.HOST_FAILURE,
                    "Bukkit main-thread action failed: " + cause,
                    cause);
        }
    }

    private Player resolve(String reference) {
        Player exact = server.getPlayerExact(reference);
        if (exact != null) {
            return exact;
        }
        try {
            return server.getPlayer(UUID.fromString(reference));
        } catch (IllegalArgumentException notAnUuid) {
            return null;
        }
    }

    private Player requirePlayer(String reference) {
        Player player = resolve(reference);
        if (player == null) {
            throw new LocalActionException(
                    LocalActionException.Kind.NOT_FOUND,
                    "player '" + reference + "' is not online");
        }
        return player;
    }

    private LocalPlayer snapshot(Player player) {
        String world = player.getWorld() == null ? null : player.getWorld().getName();
        return new LocalPlayer(
                player.getUniqueId().toString(), player.getName(), world);
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static final class BukkitLocalEvent implements LocalEvent {

        private final Event event;

        private BukkitLocalEvent(Event event) {
            this.event = event;
        }

        @Override
        public Map<String, Object> info() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", event.getClass().getSimpleName());
            boolean cancellable = event instanceof Cancellable;
            info.put("cancellable", cancellable);
            info.put("cancelled", cancellable
                    && ((Cancellable) event).isCancelled());
            if (event instanceof PlayerEvent playerEvent) {
                Player player = playerEvent.getPlayer();
                if (player != null) {
                    info.put("player", player.getName());
                }
            } else if (event instanceof PlayerDeathEvent death) {
                Player player = death.getEntity();
                if (player != null) {
                    info.put("player", player.getName());
                }
            }
            return info;
        }

        @Override
        public void cancel() {
            if (!(event instanceof Cancellable cancellable)) {
                throw new LocalActionException(
                        LocalActionException.Kind.UNSUPPORTED,
                        "Bukkit event " + event.getClass().getSimpleName()
                                + " cannot be cancelled");
            }
            cancellable.setCancelled(true);
        }
    }
}
