package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.inworld.InWorldHandler;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpigotInWorldListenerTest {

    private Server server;
    private Player player;
    private SpigotInWorldListener listener;

    @BeforeEach
    void setUp() {
        server = mock(Server.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");

        listener = new SpigotInWorldListener(
            new InWorldHandler(ChannelRegistry.builder().build(), noopLogger()),
            null,
            null,
            server,
            noopLogger(),
            noLanguages());
    }

    @Test
    void returnsPlayersInWorldAsNeutralActors() {
        World world = mock(World.class);
        when(server.getWorld("world")).thenReturn(world);
        when(world.getPlayers()).thenReturn(List.of(player));

        List<Actor> actors = listener.playersInWorld("world");

        assertThat(actors).hasSize(1);
        assertThat(actors.get(0).name()).isEqualTo("Steve");
        assertThat(actors.get(0).kind()).isEqualTo(Actor.ActorKind.PLAYER);
    }

    @Test
    void filtersPlayersInRadius() {
        World world = mock(World.class);
        Player near = mock(Player.class);
        Player far = mock(Player.class);
        when(near.getUniqueId()).thenReturn(UUID.randomUUID());
        when(near.getName()).thenReturn("Near");
        when(far.getUniqueId()).thenReturn(UUID.randomUUID());
        when(far.getName()).thenReturn("Far");
        when(near.getLocation()).thenReturn(new Location(world, 1, 64, 1));
        when(far.getLocation()).thenReturn(new Location(world, 20, 64, 20));
        when(server.getWorld("world")).thenReturn(world);
        when(world.getPlayers()).thenReturn(List.of(near, far));

        List<Actor> actors = listener.playersInRadius("world", 0, 64, 0, 5);

        assertThat(actors).extracting(Actor::name).containsExactly("Near");
    }

    @Test
    void ignoresSignsWithoutAConfiguredChannel() {
        SignChangeEvent event = mock(SignChangeEvent.class);
        Block block = mock(Block.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");

        listener.onSignChange(event);

        verify(event, never()).setLine(anyInt(), anyString());
    }

    @Test
    void returnsEmptyWhenTheWorldIsMissing() {
        when(server.getWorld("missing")).thenReturn(null);

        assertThat(listener.playersInWorld("missing")).isEmpty();
        assertThat(listener.playersInRadius("missing", 0, 64, 0, 5)).isEmpty();
    }

    @Test
    void openUrlSendsAClickableMessage() {
        Actor actor = actor(player);

        listener.openUrl(actor, "https://example.test");

        verify(player).sendMessage(contains("open_url"));
    }

    @Test
    void runCommandExecutesAsThePlayer() {
        Actor actor = actor(player);

        listener.runCommand(actor, "/spawn");

        verify(player).performCommand("/spawn");
    }

    private static Actor actor(Player player) {
        return new Actor(player.getUniqueId(), player.getName(),
            Actor.ActorKind.PLAYER, Language.EN, player);
    }

    private static UserLanguageStore noLanguages() {
        return new UserLanguageStore() {
            @Override public Optional<String> languageOf(UUID uuid) { return Optional.empty(); }
            @Override public void save(UUID uuid, String value) { }
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
