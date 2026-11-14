package me.majhrs16.suite.syncfabric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import me.majhrs16.suite.synccommon.LocalActionException;
import me.majhrs16.suite.synccommon.LocalEnvironment;
import me.majhrs16.suite.synccommon.LocalEvent;
import me.majhrs16.suite.synccommon.LocalPlayer;

import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Fabric implementation of the neutral {@link LocalEnvironment}.
 *
 * <p>Keeps every Minecraft/Fabric type inside the {@code sync-fabric} package.
 * The current event is bound per thread around the dispatch call. Fabric API
 * chat callbacks are notifications, not cancellable event objects, so
 * {@link LocalEvent#cancel()} fails explicitly for the bound chat event instead
 * of pretending to cancel it.</p>
 */
public final class FabricLocalEnvironment implements LocalEnvironment {

    private final MinecraftServer server;
    private final ThreadLocal<LocalEvent> bound = new ThreadLocal<>();

    public FabricLocalEnvironment(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Binds {@code event} to the current thread while {@code action} runs.
     *
     * @param event  platform event that originated the message
     * @param action dispatch call executed with the binding active
     */
    public void withEvent(LocalEvent event, Runnable action) {
        LocalEvent previous = bound.get();
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
    public List<LocalPlayer> players() {
        return onServerThread(() -> server.getPlayerManager().getPlayerList().stream()
                .map(this::snapshot)
                .toList());
    }

    @Override
    public Optional<LocalPlayer> player(String reference) {
        return onServerThread(() -> {
            ServerPlayerEntity player = resolve(reference);
            return player == null ? Optional.empty() : Optional.of(snapshot(player));
        });
    }

    @Override
    public void sendMessage(String reference, String text) {
        onServerThread(() -> {
            requirePlayer(reference).sendMessage(Text.literal(text));
            return null;
        });
    }

    @Override
    public void kick(String reference, String reason) {
        onServerThread(() -> {
            requirePlayer(reference).networkHandler.disconnect(Text.literal(reason));
            return null;
        });
    }

    @Override
    public void runConsoleCommand(String command) {
        onServerThread(() -> {
            try {
                server.getCommandManager().executeWithPrefix(
                        server.getCommandSource(), command);
            } catch (RuntimeException failure) {
                throw new LocalActionException(
                        LocalActionException.Kind.HOST_FAILURE,
                        "Fabric rejected the console command: " + describe(failure),
                        failure);
            }
            return null;
        });
    }

    @Override
    public void setScore(String objective, String entry, int value) {
        onServerThread(() -> {
            Scoreboard scoreboard = server.getScoreboard();
            ScoreboardObjective target = scoreboard.getNullableObjective(objective);
            if (target == null) {
                throw new LocalActionException(
                        LocalActionException.Kind.NOT_FOUND,
                        "scoreboard objective '" + objective + "' does not exist");
            }
            ScoreAccess score = scoreboard.getOrCreateScore(
                    ScoreHolder.fromName(entry), target);
            score.setScore(value);
            return null;
        });
    }

    @Override
    public Optional<LocalEvent> currentEvent() {
        return Optional.ofNullable(bound.get());
    }

    /**
     * Runs {@code action} on the server thread, waiting for completion.
     *
     * <p>Player, command and scoreboard access is server-thread only. Waiting
     * keeps the action result honest: no success is reported before the server
     * executed it.</p>
     */
    private <T> T onServerThread(Supplier<T> action) {
        if (server.isOnThread()) {
            return action.get();
        }
        try {
            return server.submit(action::get).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LocalActionException(
                    LocalActionException.Kind.HOST_FAILURE,
                    "interrupted while waiting for the Fabric server thread",
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
                    "Fabric server-thread action failed: " + cause,
                    cause);
        }
    }

    private ServerPlayerEntity resolve(String reference) {
        ServerPlayerEntity byName = server.getPlayerManager().getPlayer(reference);
        if (byName != null) {
            return byName;
        }
        try {
            return server.getPlayerManager().getPlayer(UUID.fromString(reference));
        } catch (IllegalArgumentException notAnUuid) {
            return null;
        }
    }

    private ServerPlayerEntity requirePlayer(String reference) {
        ServerPlayerEntity player = resolve(reference);
        if (player == null) {
            throw new LocalActionException(
                    LocalActionException.Kind.NOT_FOUND,
                    "player '" + reference + "' is not online");
        }
        return player;
    }

    private LocalPlayer snapshot(ServerPlayerEntity player) {
        String world = player.getWorld().getRegistryKey().getValue().toString();
        return new LocalPlayer(
                player.getUuid().toString(), player.getName().getString(), world);
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /**
     * Immutable event snapshot plus an optional cancellation hook.
     *
     * <p>A null hook means the Fabric callback cannot be cancelled after the
     * fact; the failure is explicit.</p>
     */
    static final class FabricLocalEvent implements LocalEvent {

        private final Map<String, Object> info;
        private final Runnable cancellation;

        FabricLocalEvent(String type, String player, Runnable cancellation) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", type);
            value.put("cancellable", cancellation != null);
            value.put("cancelled", false);
            if (player != null) {
                value.put("player", player);
            }
            this.info = Map.copyOf(value);
            this.cancellation = cancellation;
        }

        @Override
        public Map<String, Object> info() {
            return info;
        }

        @Override
        public void cancel() {
            if (cancellation == null) {
                throw new LocalActionException(
                        LocalActionException.Kind.UNSUPPORTED,
                        "Fabric chat callbacks cannot be cancelled after dispatch");
            }
            cancellation.run();
        }
    }
}
