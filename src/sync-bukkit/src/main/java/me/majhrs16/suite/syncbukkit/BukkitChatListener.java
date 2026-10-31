package me.majhrs16.suite.syncbukkit;

import java.util.Objects;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.synccommon.EventRules;
import me.majhrs16.suite.synccommon.SyncEventProcessor;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Bukkit event capture owned by the {@code sync-bukkit} package.
 *
 * <p>It claims the vanilla chat event according to the package configuration,
 * translates the native events into neutral calls and never touches the host
 * pipeline directly. Chat runs on the async chat thread; join/quit/death are
 * offloaded to the module scheduler so translation cannot block the server
 * tick.</p>
 */
public final class BukkitChatListener implements Listener {

    private final SyncEventProcessor processor;
    private final ActorDirectory actors;
    private final SyncBukkitConfig config;
    private final Scheduler scheduler;
    private final PluginLogger logger;
    private volatile boolean active = true;

    public BukkitChatListener(SyncEventProcessor processor,
                              ActorDirectory actors,
                              SyncBukkitConfig config,
                              Scheduler scheduler,
                              PluginLogger logger) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!active) {
            return;
        }
        switch (config.claimMode()) {
            case CANCEL_EVENT -> event.setCancelled(true);
            case CLEAR_RECIPIENTS -> event.getRecipients().clear();
            case NONE -> {
                // Vanilla chat remains visible by explicit configuration.
            }
        }
        processor.chat(actor(event.getPlayer()), event.getMessage(), config.logChatToConsole());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getPlayer();
        Actor actor = actor(player);
        scheduler.execute(() -> processor.typed(
                MessageType.JOIN, EventRules.CHANNEL_JOIN, actor, player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getPlayer();
        Actor actor = actor(player);
        scheduler.execute(() -> processor.typed(
                MessageType.LEAVE, EventRules.CHANNEL_QUIT, actor, player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getEntity();
        Actor actor = actor(player);
        String text = event.getDeathMessage() == null
                ? player.getName()
                : event.getDeathMessage();
        scheduler.execute(() -> processor.typed(
                MessageType.DEATH, EventRules.CHANNEL_DEATH, actor, text));
    }

    /** Stops handling events; the activator unregisters the listener afterwards. */
    public void deactivate() {
        if (active) {
            active = false;
            logger.debug("sync-bukkit listener deactivated");
        }
    }

    /** @return {@code true} while the listener is allowed to handle events. */
    public boolean isActive() {
        return active;
    }

    private Actor actor(Player player) {
        return actors.byUuid(player.getUniqueId()).orElseGet(() -> new Actor(
                player.getUniqueId(), player.getName(),
                Actor.ActorKind.PLAYER, null, player));
    }
}
