package me.majhrs16.suite.syncvelocity.velocity;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.velocity.VelocityListener;
import me.majhrs16.suite.api.velocity.VelocitySettings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityProxyConnectionTest {

    private ProxyServer proxy;
    private ChannelRegistrar registrar;
    private EventManager events;
    private Object plugin;
    private VelocityProxyConnection connection;

    @BeforeEach
    void setUp() {
        proxy = mock(ProxyServer.class);
        registrar = mock(ChannelRegistrar.class);
        events = mock(EventManager.class);
        plugin = new Object();
        when(proxy.getChannelRegistrar()).thenReturn(registrar);
        when(proxy.getEventManager()).thenReturn(events);
        connection = new VelocityProxyConnection(
            proxy, plugin, VelocitySettings.defaults(), new NoopLogger());
    }

    @Test
    void registersAndUnregistersTheChannelAndListener() {
        connection.start(payload -> { });
        connection.start(payload -> { });

        ArgumentCaptor<ChannelIdentifier> channel = ArgumentCaptor.forClass(ChannelIdentifier.class);
        verify(registrar).register(channel.capture());
        assertEquals(MinecraftChannelIdentifier.from("textformatter:velocity"), channel.getValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventHandler<PluginMessageEvent>> handler =
            ArgumentCaptor.forClass(EventHandler.class);
        verify(events).register(org.mockito.ArgumentMatchers.eq(plugin),
            org.mockito.ArgumentMatchers.eq(PluginMessageEvent.class), handler.capture());

        connection.stop();
        connection.stop();
        verify(events).unregisterListener(plugin, handler.getValue());
        verify(registrar).unregister(channel.getValue());
    }

    @Test
    void forwardsOnlyTheConfiguredChannel() {
        List<byte[]> received = new ArrayList<>();
        connection.start(received::add);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventHandler<PluginMessageEvent>> handler =
            ArgumentCaptor.forClass(EventHandler.class);
        verify(events).register(org.mockito.ArgumentMatchers.eq(plugin),
            org.mockito.ArgumentMatchers.eq(PluginMessageEvent.class), handler.capture());

        PluginMessageEvent matching = mock(PluginMessageEvent.class);
        when(matching.getIdentifier()).thenReturn(
            MinecraftChannelIdentifier.from("textformatter:velocity"));
        when(matching.getData()).thenReturn(new byte[] {1, 2, 3});
        handler.getValue().execute(matching);

        PluginMessageEvent other = mock(PluginMessageEvent.class);
        when(other.getIdentifier()).thenReturn(
            MinecraftChannelIdentifier.from("other:channel"));
        handler.getValue().execute(other);

        assertEquals(1, received.size());
        assertArrayEquals(new byte[] {1, 2, 3}, received.get(0));
    }

    @Test
    void sendsToKnownServersOnly() {
        RegisteredServer lobby = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn("lobby");
        when(lobby.getServerInfo()).thenReturn(info);
        when(proxy.getAllServers()).thenReturn(List.of(lobby));
        when(proxy.getServer("lobby")).thenReturn(Optional.of(lobby));
        when(proxy.getServer("missing")).thenReturn(Optional.empty());
        when(lobby.sendPluginMessage(any(), any(byte[].class))).thenReturn(true);

        assertFalse(connection.send("lobby", new byte[] {1}));

        connection.start(payload -> { });
        assertEquals(List.of("lobby"), connection.servers());
        assertTrue(connection.send("lobby", new byte[] {1}));
        assertFalse(connection.send("missing", new byte[] {1}));
        verify(lobby).sendPluginMessage(any(), any(byte[].class));
    }

    @Test
    void closeStopsIdempotently() {
        connection.start(payload -> { });
        connection.close();
        connection.close();

        ArgumentCaptor<ChannelIdentifier> channel = ArgumentCaptor.forClass(ChannelIdentifier.class);
        verify(registrar).unregister(channel.capture());
        verify(events).unregisterListener(org.mockito.ArgumentMatchers.eq(plugin), any());
    }

    private static final class NoopLogger implements PluginLogger {
        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}
