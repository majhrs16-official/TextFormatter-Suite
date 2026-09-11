package me.majhrs16.suite.syncvelocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MessageCallback;
import com.velocitypowered.api.plugin.PluginContainer;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.transport.MessageCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

/**
 * Velocity edge connector: forwards messages to other servers via Velocity's
 * plugin messaging channel and receives inbound messages from the proxy.
 *
 * <p>Protocol: Uses the {@code textformatter:velocity} plugin messaging channel.
 * Messages are serialized as JSON via {@link MessageCodec} and sent as BungeeCord-style
 * plugin messages to the Velocity proxy, which forwards them to the target server.</p>
 *
 * <p>Configuration (from {@code sync/velocity.yml}):
 * <pre>
 *   enabled: true
 *   secret: "shared-secret-for-auth"
 *   servers:
 *     - "server1"
 *     - "server2"
 *   mapping: "* -> chat.hub"
 * </pre>
 */
public final class VelocitySink implements SyncSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(VelocitySink.class);

    private static final String CHANNEL_NAME = "textformatter:velocity";
    private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(CHANNEL_NAME);

    private final ProxyServer proxy;
    private final String secret;
    private final java.util.List<String> servers;
    private final String mapping;
    private final AtomicReference<SyncListener> listenerRef = new AtomicReference<>();

    public VelocitySink(ProxyServer proxy, String secret, java.util.List<String> servers, String mapping) {
        this.proxy = proxy;
        this.secret = secret;
        this.servers = servers != null ? java.util.List.copyOf(servers) : java.util.List.of();
        this.mapping = mapping != null ? mapping : "* -> chat.hub";
    }

    @Override
    public String name() {
        return "velocity";
    }

    @Override
    public void start() {
        // Register the channel for inbound messages
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getEventManager().register(proxy.getPluginManager().getPlugin("textformatter-suite").orElseThrow(),
            com.velocitypowered.api.event.player.ServerConnectedEvent.class, event -> {
                // Server connected - could log or track
            });
        LOGGER.info("VelocitySink started on channel {}", CHANNEL_NAME);
    }

    @Override
    public void stop() {
        proxy.getChannelRegistrar().unregister(CHANNEL);
        LOGGER.info("VelocitySink stopped");
    }

    @Override
    public void send(Message message) throws IOException, InterruptedException {
        // Serialize message to JSON
        String json = MessageCodec.toJson(message).toString();

        // Build the plugin message payload
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("textformatter");
        out.writeUTF(message.type().name());
        out.writeUTF(message.id().toString());
        out.writeUTF(message.sender() != null ? message.sender().name() : "CONSOLE");
        out.writeUTF(message.channel());
        out.writeUTF(json);
        out.writeUTF(message.sender() != null ? message.sender().uuid().toString() : "");
        out.writeLong(message.timestamp().toEpochMilli());

        // Send to all configured servers (or broadcast to all if mapping is "*")
        String target = parseMapping(message.channel());
        if ("*".equals(target) || "all".equalsIgnoreCase(target)) {
            broadcastToAllServers(out);
        } else {
            sendToServer(target, out);
        }
    }

    private String parseMapping(String channel) {
        if (mapping == null || mapping.isBlank()) {
            return "*";
        }
        // Simple mapping: "source -> target"
        String[] parts = mapping.split("->");
        if (parts.length == 2) {
            String source = parts[0].trim();
            String target = parts[1].trim();
            if ("*".equals(source) || source.equals(channel)) {
                return target;
            }
        }
        return channel; // fallback: same channel name
    }

    private void broadcastToAllServers(ByteArrayDataOutput out) {
        byte[] data = out.toByteArray();
        for (String serverName : servers) {
            sendToServer(serverName, data);
        }
        // Also send to all connected servers if no explicit list
        if (servers.isEmpty()) {
            for (com.velocitypowered.api.proxy.ServerConnection server : proxy.getAllServers()) {
                sendToServerConnection(server, data);
            }
        }
    }

    private void sendToServer(String serverName, ByteArrayDataOutput out) {
        sendToServer(serverName, out.toByteArray());
    }

    private void sendToServer(String serverName, byte[] data) {
        Optional<com.velocitypowered.api.proxy.ServerConnection> server = proxy.getServer(serverName);
        if (server.isPresent()) {
            sendToServerConnection(server.get(), data);
        } else {
            LOGGER.warn("Velocity server not found: {}", serverName);
        }
    }

    private void sendToServerConnection(com.velocitypowered.api.proxy.ServerConnection server, byte[] data) {
        server.sendPluginMessage(CHANNEL, data)
            .exceptionally(throwable -> {
                LOGGER.error("Failed to send plugin message to server {}: {}", server.getServerInfo().getName(), throwable.getMessage());
                return null;
            });
    }

    @Override
    public void setListener(SyncListener listener) {
        // Register inbound message handler
        proxy.getEventManager().register(proxy.getPluginManager().getPlugin("textformatter-suite").orElseThrow(),
            com.velocitypowered.api.proxy.messages.PluginMessageEvent.class, event -> {
                if (!event.getIdentifier().equals(CHANNEL)) {
                    return;
                }
                try {
                    // Parse inbound plugin message
                    com.google.common.io.ByteArrayDataInput in = com.google.common.io.ByteStreams.newDataInput(event.getData());
                    String subChannel = in.readUTF();
                    if (!"textformatter".equals(subChannel)) {
                        return;
                    }
                    String typeStr = in.readUTF();
                    String idStr = in.readUTF();
                    String senderName = in.readUTF();
                    String channel = in.readUTF();
                    String json = in.readUTF();
                    String senderUuid = in.readUTF();
                    long timestamp = in.readLong();

                    // Verify secret if provided
                    if (secret != null && !secret.isBlank()) {
                        String auth = in.readUTF();
                        if (!secret.equals(auth)) {
                            LOGGER.warn("Velocity auth failed from {}", event.getSource());
                            return;
                        }
                    }

                    Message message = MessageCodec.fromJson(json);
                    SyncListener current = listenerRef.get();
                    if (current != null) {
                        current.onMessage(this, message);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to parse inbound Velocity message", e);
                }
            });
        listenerRef.set(listener);
    }

    @Override
    public void setListener(SyncListener listener) {
        listenerRef.set(listener);
    }
}