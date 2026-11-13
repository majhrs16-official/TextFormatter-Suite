package me.majhrs16.suite.syncsocket;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.api.sync.SyncValueType;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw UDP edge: outbound sends one datagram per message to a remote listener;
 * inbound binds a local port, reassembles line-terminated datagrams and feeds
 * the decoder through {@link SyncListener}.
 *
 * <p>The sink publishes the official least common multiple plus
 * {@code endpoint.info}, which reports the configured endpoint and the bound
 * local port without exposing credentials.</p>
 */
public final class UdpSink implements SyncSink {

    private static final int MAX_DATAGRAM = 65_507;

    private static final String ENDPOINT_INFO = "endpoint.info";
    private static final Set<String> NATIVE_ACTIONS = Set.of(ENDPOINT_INFO);
    private static final SyncSurface SURFACE = buildSurface();

    private final String remoteHost;
    private final int remotePort;
    private final int localPort;
    private volatile SyncListener listener;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile DatagramSocket socket;
    private volatile Thread receiveThread;

    public UdpSink(String remoteHost, int remotePort, int localPort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
    }

    @Override
    public String name() {
        return "udp";
    }

    @Override
    public SyncSurface surface() {
        return SURFACE;
    }

    @Override
    public DeliverySemantics deliverySemantics() {
        return DeliverySemantics.BEST_EFFORT;
    }

    @Override
    public Ordering ordering() {
        return Ordering.NONE;
    }

    /**
     * Executes the least common multiple through the default implementation and
     * {@code endpoint.info} through this sink. The native action requires a
     * {@link Principal.Program}; {@link Principal.Official} only reaches the
     * LCM, and a stopped sink returns {@link SyncResult.HostFailure}.
     */
    @Override
    public SyncResult invoke(SyncCall call) {
        Objects.requireNonNull(call, "call");
        if (!name().equals(call.backend()) || !NATIVE_ACTIONS.contains(call.action())) {
            return SyncSink.super.invoke(call);
        }
        if (call.principal() instanceof Principal.Official) {
            return new SyncResult.Denied(
                "Principal.Official may only invoke the least common multiple");
        }
        if (!running.get()) {
            return new SyncResult.HostFailure("udp sink is stopped");
        }
        return new SyncResult.Ok(endpointInfo());
    }

    /** @return the actual bound inbound port once started (0 = ephemeral). */
    public int inboundPort() {
        DatagramSocket current = socket;
        return current != null ? current.getLocalPort() : localPort;
    }

    /** @return whether the inbound listener is running. */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        DatagramSocket created = new DatagramSocket(localPort);
        socket = created;
        running.set(true);
        receiveThread = new Thread(() -> receiveLoop(created), "udp-sink-inbound");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
        Thread thread = receiveThread;
        receiveThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void send(Message message) throws IOException {
        DatagramSocket current = socket;
        if (!running.get() || current == null) {
            throw new IOException("udp sink is stopped");
        }
        byte[] payload = me.majhrs16.suite.transport.MessageCodec.toJsonString(message)
            .getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, payload.length,
            new InetSocketAddress(remoteHost, remotePort));
        current.send(packet);
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    private void receiveLoop(DatagramSocket current) {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (running.get() && !current.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                current.receive(packet);
                String line = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8);
                if (line.isBlank()) {
                    continue;
                }
                SyncListener currentListener = listener;
                if (currentListener != null) {
                    currentListener.onMessage(this,
                        me.majhrs16.suite.transport.MessageCodec.fromJsonString(line));
                }
            } catch (IOException e) {
                if (running.get() && !current.isClosed()) {
                    SyncListener currentListener = listener;
                    if (currentListener != null) {
                        currentListener.onDisconnect(this, e.getMessage());
                    }
                } else {
                    break;
                }
            }
        }
    }

    /** Describes the configured endpoint without exposing credentials. */
    private Map<String, Object> endpointInfo() {
        DatagramSocket current = socket;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name());
        info.put("running", running.get());
        info.put("remoteHost", remoteHost);
        info.put("remotePort", remotePort);
        info.put("inboundPort", inboundPort());
        info.put("inboundListening", current != null && !current.isClosed());
        return info;
    }

    private static SyncSurface buildSurface() {
        SyncSurface lcm = SyncSurface.lcm("udp", SyncSurface.CURRENT_CONTRACT_VERSION);
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
            ENDPOINT_INFO,
            ActionKind.READ,
            List.of(),
            SyncParamSpec.optional("info", SyncValueType.OBJECT),
            true,
            5_000));
        return new SyncSurface("udp", SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }
}
