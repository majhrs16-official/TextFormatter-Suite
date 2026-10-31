package me.majhrs16.suite.syncfabric;

import java.util.Objects;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.synccommon.EventRules;
import me.majhrs16.suite.synccommon.SyncEventProcessor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Fabric event capture owned by the {@code sync-fabric} package.
 *
 * <p>Fabric API registrations are global and have no unregister operation. The
 * callbacks therefore check an {@code active} flag and the activator marks the
 * module {@code RESTART_REQUIRED} when it is deactivated outside a full server
 * shutdown.</p>
 */
public final class FabricEventListener {

    private final SyncEventProcessor processor;
    private final ActorDirectory actors;
    private final SyncFabricConfig config;
    private final Scheduler scheduler;
    private final PluginLogger logger;
    private final FabricDeathTracker deaths = new FabricDeathTracker();
    private volatile boolean active = true;

    public FabricEventListener(SyncEventProcessor processor,
                               ActorDirectory actors,
                               SyncFabricConfig config,
                               Scheduler scheduler,
                               PluginLogger logger) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Registers the global Fabric callbacks. Called once from the activator. */
    public void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (!active) {
                return;
            }
            Actor actor = actor(sender);
            String text = message.getContent().getString();
            scheduler.execute(() -> processor.chat(actor, text, config.logChatToConsole()));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!active) {
                return;
            }
            ServerPlayerEntity player = handler.player;
            Actor actor = actor(player);
            String name = player.getName().getString();
            scheduler.execute(() -> processor.typed(
                    MessageType.JOIN, EventRules.CHANNEL_JOIN, actor, name));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!active) {
                return;
            }
            ServerPlayerEntity player = handler.player;
            Actor actor = actor(player);
            String name = player.getName().getString();
            scheduler.execute(() -> processor.typed(
                    MessageType.LEAVE, EventRules.CHANNEL_QUIT, actor, name));
        });

        ServerTickEvents.END_SERVER_TICK.register(this::observeDeaths);
        logger.info("sync-fabric callbacks registered");
    }

    private void observeDeaths(MinecraftServer server) {
        if (!active) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!player.isAlive()) {
                if (deaths.markDead(player.getUuid())) {
                    Actor actor = actor(player);
                    String text = player.getDamageTracker().getDeathMessage() == null
                            ? player.getName().getString()
                            : player.getDamageTracker().getDeathMessage().getString();
                    scheduler.execute(() -> processor.typed(
                            MessageType.DEATH, EventRules.CHANNEL_DEATH, actor, text));
                }
            } else {
                deaths.markAlive(player.getUuid());
            }
        }
    }

    /** Makes every callback inert; Fabric API cannot unregister them. */
    public void deactivate() {
        active = false;
        deaths.clear();
    }

    public boolean isActive() {
        return active;
    }

    private Actor actor(ServerPlayerEntity player) {
        return actors.byUuid(player.getUuid()).orElseGet(() -> new Actor(
                player.getUuid(), player.getName().getString(),
                Actor.ActorKind.PLAYER, null, player));
    }
}
