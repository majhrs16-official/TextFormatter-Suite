package me.majhrs16.suite.syncbukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.synccommon.EventRules;
import me.majhrs16.suite.synccommon.SyncEventProcessor;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

class BukkitChatListenerTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final Actor PLAYER =
            new Actor(PLAYER_ID, "Steve", Actor.ActorKind.PLAYER, Language.EN, null);

    @Test
    void cancelEventClaimsAndDispatchesChat() {
        SyncEventProcessor processor = mock(SyncEventProcessor.class);
        Player player = player();
        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn("hello");

        listener(processor, SyncBukkitConfig.ClaimMode.CANCEL_EVENT, true).onChat(event);

        verify(event).setCancelled(true);
        verify(processor).chat(PLAYER, "hello", true);
    }

    @Test
    void clearRecipientsEmptiesTheVanillaAudience() {
        SyncEventProcessor processor = mock(SyncEventProcessor.class);
        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        Player player = player();
        Set<Player> recipients = new HashSet<>();
        recipients.add(player);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn("hello");
        when(event.getRecipients()).thenReturn(recipients);

        listener(processor, SyncBukkitConfig.ClaimMode.CLEAR_RECIPIENTS, false).onChat(event);

        assertTrue(recipients.isEmpty());
        verify(event, never()).setCancelled(true);
        verify(processor).chat(PLAYER, "hello", false);
    }

    @Test
    void noneLeavesVanillaChatAlone() {
        SyncEventProcessor processor = mock(SyncEventProcessor.class);
        Player player = player();
        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn("hello");

        listener(processor, SyncBukkitConfig.ClaimMode.NONE, false).onChat(event);

        verify(event, never()).setCancelled(true);
        verify(processor).chat(PLAYER, "hello", false);
    }

    @Test
    void typedEventsAreOffloadedAndUseConventionalChannels() {
        SyncEventProcessor processor = mock(SyncEventProcessor.class);
        BukkitChatListener listener = listener(
                processor, SyncBukkitConfig.ClaimMode.CANCEL_EVENT, false);

        Player player = player();
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(player);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        when(death.getDeathMessage()).thenReturn("Steve died");

        listener.onJoin(join);
        listener.onQuit(quit);
        listener.onDeath(death);

        verify(processor).typed(MessageType.JOIN, EventRules.CHANNEL_JOIN, PLAYER, "Steve");
        verify(processor).typed(MessageType.LEAVE, EventRules.CHANNEL_QUIT, PLAYER, "Steve");
        verify(processor).typed(MessageType.DEATH, EventRules.CHANNEL_DEATH, PLAYER, "Steve died");
    }

    @Test
    void deactivateStopsHandlingEvents() {
        SyncEventProcessor processor = mock(SyncEventProcessor.class);
        BukkitChatListener listener = listener(
                processor, SyncBukkitConfig.ClaimMode.CANCEL_EVENT, true);
        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);

        listener.deactivate();
        listener.onChat(event);

        assertFalse(listener.isActive());
        verify(processor, never()).chat(any(), any(), anyBoolean());
    }

    private static BukkitChatListener listener(SyncEventProcessor processor,
                                               SyncBukkitConfig.ClaimMode mode,
                                               boolean logToConsole) {
        ActorDirectory actors = mock(ActorDirectory.class);
        when(actors.byUuid(PLAYER_ID)).thenReturn(Optional.of(PLAYER));
        return new BukkitChatListener(
                processor,
                actors,
                new SyncBukkitConfig(true, mode, logToConsole),
                inlineScheduler(),
                noopLogger());
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Steve");
        return player;
    }

    private static Scheduler inlineScheduler() {
        return new Scheduler() {
            @Override public Cancellable schedule(Runnable task, long delayMillis) {
                task.run();
                return cancelled();
            }
            @Override public Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
                task.run();
                return cancelled();
            }
            @Override public Cancellable execute(Runnable task) {
                task.run();
                return cancelled();
            }
        };
    }

    private static Scheduler.Cancellable cancelled() {
        return new Scheduler.Cancellable() {
            @Override public void cancel() { }
            @Override public boolean isCancelled() { return true; }
        };
    }

    private static PluginLogger noopLogger() {
        return new PluginLogger() {
            @Override public void info(String message, Object... args) { }
            @Override public void warn(String message, Object... args) { }
            @Override public void error(String message, Object... args) { }
            @Override public void error(String message, Throwable throwable) { }
            @Override public void debug(String message, Object... args) { }
        };
    }
}
