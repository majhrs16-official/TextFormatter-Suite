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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw TCP edge: outbound connects to a remote listener and writes one JSON
 * line per message; inbound listens on a local port, one line per accepted
 * connection, and feeds the decoder through {@link SyncListener}.
 * <p>
 * Security: Uses socket timeouts to prevent slow-client DoS attacks.
 * </p>
 *
 * <p>The sink publishes the official least common multiple plus
 * {@code endpoint.info} and {@code connection.probe}, which lets a program
 * inspect the configured endpoint and verify that the remote listener accepts
 * TCP connections without sending a message.</p>
 */
public final class TcpSink implements SyncSink {

    private static final int SOCKET_TIMEOUT_MS = 30000; // 30 seconds
    private static final int PROBE_TIMEOUT_MS = 4000;

    private static final String ENDPOINT_INFO = "endpoint.info";
    private static final String CONNECTION_PROBE = "connection.probe";
    private static final Set<String> NATIVE_ACTIONS =
        Set.of(ENDPOINT_INFO, CONNECTION_PROBE);
    private static final SyncSurface SURFACE = buildSurface();

    private final String remoteHost;
    private final int remotePort;
    private final int localPort;
    private volatile SyncListener listener;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private volatile Socket pendingSocket;

    public TcpSink(String remoteHost, int remotePort, int localPort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
    }

    @Override
    public String name() {
        return "tcp";
    }

    @Override
    public SyncSurface surface() {
        return SURFACE;
    }

    @Override
    public DeliverySemantics deliverySemantics() {
        return DeliverySemantics.AT_LEAST_ONCE;
    }

    @Override
    public Ordering ordering() {
        return Ordering.PER_CHANNEL;
    }

    /**
     * Executes the least common multiple through the default implementation and
     * the TCP-native actions through this sink. Native actions require a
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
            return new SyncResult.HostFailure("tcp sink is stopped");
        }
        if (CONNECTION_PROBE.equals(call.action())) {
            return probe();
        }
        return new SyncResult.Ok(endpointInfo());
    }

    /** @return the actual bound inbound port once started (0 = ephemeral). */
    public int inboundPort() {
        ServerSocket current = server;
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
        ServerSocket created = new ServerSocket(localPort);
        try {
            // Set socket timeout to prevent slow-client DoS
            created.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (IOException failure) {
            created.close();
            throw failure;
        }
        server = created;
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "tcp-sink-inbound");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        ServerSocket current = server;
        server = null;
        try {
            if (current != null) {
                current.close();
            }
            if (pendingSocket != null) {
                pendingSocket.close();
            }
        } catch (IOException e) {
            // best effort: the accept loop is daemon and exits on running=false
        }
        Thread thread = acceptThread;
        acceptThread = null;
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
        if (!running.get()) {
            throw new IOException("tcp sink is stopped");
        }
        try (Socket socket = new Socket()) {
            // Set connection timeout to prevent blocking indefinitely
            socket.connect(new InetSocketAddress(remoteHost, remotePort), SOCKET_TIMEOUT_MS);
            // Also set read/write timeout
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(),
                StandardCharsets.UTF_8);
            writer.write(me.majhrs16.suite.transport.MessageCodec.toJsonString(message));
            writer.write("\n");
            writer.flush();
        }
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    private void acceptLoop() {
        ServerSocket current = server;
        if (current == null) {
            return;
        }
        while (running.get() && !current.isClosed()) {
            try {
                Socket socket = current.accept();
                // Set timeout on accepted socket to prevent slow-client DoS
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                pendingSocket = socket;
                try (socket) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    SyncListener currentListener = listener;
                    if (currentListener != null) {
                        currentListener.onMessage(this,
                            me.majhrs16.suite.transport.MessageCodec.fromJsonString(line));
                    }
                }
            } catch (SocketTimeoutException e) {
                // Accept timeout - just continue the loop
                if (!running.get() || current.isClosed()) break;
            } catch (IOException e) {
                if (running.get() && !current.isClosed()) {
                    notifyDisconnect(e.getMessage());
                } else {
                    break;
                }
            }
        }
    }

    private void notifyDisconnect(String reason) {
        SyncListener current = listener;
        if (current != null) {
            current.onDisconnect(this, reason);
        }
    }

    /** Describes the configured endpoint without exposing credentials. */
    private Map<String, Object> endpointInfo() {
        ServerSocket current = server;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name());
        info.put("running", running.get());
        info.put("remoteHost", remoteHost);
        info.put("remotePort", remotePort);
        info.put("inboundPort", inboundPort());
        info.put("inboundListening", current != null && !current.isClosed());
        return info;
    }

    /** Verifies that the remote listener accepts a TCP connection. */
    private SyncResult probe() {
        long started = System.nanoTime();
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(remoteHost, remotePort), PROBE_TIMEOUT_MS);
            long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("reachable", true);
            value.put("remoteHost", remoteHost);
            value.put("remotePort", remotePort);
            value.put("latencyMillis", latency);
            return new SyncResult.Ok(value);
        } catch (IOException failure) {
            return new SyncResult.HostFailure("tcp probe failed: " + describe(failure));
        }
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }

    private static SyncSurface buildSurface() {
        SyncSurface lcm = SyncSurface.lcm("tcp", SyncSurface.CURRENT_CONTRACT_VERSION);
        Set<SyncActionSpec> actions = new LinkedHashSet<>(lcm.actions());
        actions.add(new SyncActionSpec(
            ENDPOINT_INFO,
            ActionKind.READ,
            List.of(),
            SyncParamSpec.optional("info", SyncValueType.OBJECT),
            true,
            5_000));
        actions.add(new SyncActionSpec(
            CONNECTION_PROBE,
            ActionKind.READ,
            List.of(),
            SyncParamSpec.optional("probe", SyncValueType.OBJECT),
            true,
            5_000));
        return new SyncSurface("tcp", SyncSurface.CURRENT_CONTRACT_VERSION, actions);
    }
}
