package me.majhrs16.suite.syncbukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import me.majhrs16.suite.synccommon.LocalActionException;
import me.majhrs16.suite.synccommon.LocalEvent;
import me.majhrs16.suite.synccommon.LocalPlayer;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BukkitLocalEnvironmentTest {

    private final UUID playerId = UUID.randomUUID();
    private Server server;
    private Player player;
    private BukkitLocalEnvironment environment;

    @BeforeEach
    void setUp() {
        server = mock(Server.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Steve");
        World world = mock(World.class);
        when(world.getName()).thenReturn("overworld");
        when(player.getWorld()).thenReturn(world);
        when(server.isPrimaryThread()).thenReturn(true);
        environment = new BukkitLocalEnvironment(plugin);
    }

    @Test
    void snapshotsOnlinePlayers() {
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        List<LocalPlayer> players = environment.players();

        assertEquals(List.of(new LocalPlayer(
                playerId.toString(), "Steve", "overworld")), players);
    }

    @Test
    void resolvesPlayersByNameOrUuid() {
        when(server.getPlayerExact("Steve")).thenReturn(player);
        when(server.getPlayer(playerId)).thenReturn(player);

        assertEquals(Optional.of(new LocalPlayer(
                playerId.toString(), "Steve", "overworld")),
                environment.player("Steve"));
        assertTrue(environment.player(playerId.toString()).isPresent());
        assertTrue(environment.player("Nobody").isEmpty());
        assertTrue(environment.player("not-a-uuid").isEmpty());
    }

    @Test
    void sendsPrivateMessagesAndKicksOnlinePlayers() {
        when(server.getPlayerExact("Steve")).thenReturn(player);

        environment.sendMessage("Steve", "hola");
        environment.kick("Steve", "spam");

        verify(player).sendMessage("hola");
        verify(player).kickPlayer("spam");
    }

    @Test
    void missingPlayersFailExplicitly() {
        LocalActionException message = assertThrows(LocalActionException.class,
                () -> environment.sendMessage("Nobody", "hola"));
        assertEquals(LocalActionException.Kind.NOT_FOUND, message.kind());

        LocalActionException kick = assertThrows(LocalActionException.class,
                () -> environment.kick("Nobody", "spam"));
        assertEquals(LocalActionException.Kind.NOT_FOUND, kick.kind());
    }

    @Test
    void runsConsoleCommandsAndReportsRejection() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(server.getConsoleSender()).thenReturn(console);
        when(server.dispatchCommand(console, "say hi")).thenReturn(true);
        when(server.dispatchCommand(console, "bad")).thenReturn(false);

        environment.runConsoleCommand("say hi");

        LocalActionException rejected = assertThrows(LocalActionException.class,
                () -> environment.runConsoleCommand("bad"));
        assertEquals(LocalActionException.Kind.HOST_FAILURE, rejected.kind());
    }

    @Test
    void setsScoresOnExistingObjectives() {
        ScoreboardManager manager = mock(ScoreboardManager.class);
        Scoreboard board = mock(Scoreboard.class);
        Objective objective = mock(Objective.class);
        Score score = mock(Score.class);
        when(server.getScoreboardManager()).thenReturn(manager);
        when(manager.getMainScoreboard()).thenReturn(board);
        when(board.getObjective("points")).thenReturn(objective);
        when(objective.getScore("Steve")).thenReturn(score);

        environment.setScore("points", "Steve", 7);

        verify(score).setScore(7);

        LocalActionException missing = assertThrows(LocalActionException.class,
                () -> environment.setScore("unknown", "Steve", 1));
        assertEquals(LocalActionException.Kind.NOT_FOUND, missing.kind());
    }

    @Test
    @SuppressWarnings("unchecked")
    void offMainThreadActionsWaitForTheMainThread() throws Exception {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(false);
        when(server.getPlayerExact("Steve")).thenReturn(player);
        doAnswer(invocation -> CompletableFuture.completedFuture(
                ((Callable<?>) invocation.getArgument(1)).call()))
                .when(scheduler).callSyncMethod(any(Plugin.class), any(Callable.class));

        environment.sendMessage("Steve", "hola");

        verify(player).sendMessage("hola");
    }

    @Test
    void bindsCancellableAndNonCancellableEvents() {
        AsyncPlayerChatEvent chat = mock(AsyncPlayerChatEvent.class);
        when(chat.getPlayer()).thenReturn(player);
        when(chat.isCancelled()).thenReturn(false);

        environment.withEvent(chat, () -> {
            LocalEvent bound = environment.currentEvent().orElseThrow();
            assertEquals("AsyncPlayerChatEvent", bound.info().get("type"));
            assertEquals(true, bound.info().get("cancellable"));
            assertEquals(false, bound.info().get("cancelled"));
            assertEquals("Steve", bound.info().get("player"));
            bound.cancel();
        });

        verify(chat).setCancelled(true);
        assertTrue(environment.currentEvent().isEmpty());

        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        environment.withEvent(join, () -> {
            LocalEvent bound = environment.currentEvent().orElseThrow();
            assertEquals(false, bound.info().get("cancellable"));
            LocalActionException failure = assertThrows(LocalActionException.class,
                    bound::cancel);
            assertEquals(LocalActionException.Kind.UNSUPPORTED, failure.kind());
        });
        assertFalse(environment.currentEvent().isPresent());
    }

    @Test
    void nestedBindingsRestoreTheOuterEvent() {
        AsyncPlayerChatEvent outer = mock(AsyncPlayerChatEvent.class);
        when(outer.getPlayer()).thenReturn(player);
        PlayerJoinEvent inner = mock(PlayerJoinEvent.class);

        environment.withEvent(outer, () -> {
            assertEquals("AsyncPlayerChatEvent",
                    environment.currentEvent().orElseThrow().info().get("type"));
            environment.withEvent(inner, () ->
                    assertEquals("PlayerJoinEvent",
                            environment.currentEvent().orElseThrow().info().get("type")));
            assertEquals("AsyncPlayerChatEvent",
                    environment.currentEvent().orElseThrow().info().get("type"));
        });
        assertTrue(environment.currentEvent().isEmpty());
    }
}
