package me.majhrs16.suite.synctcpudp;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw TCP edge: outbound connects to a remote listener and writes one JSON
 * line per message; inbound listens on a local port, one line per accepted
 * connection, and feeds the decoder through {@link SyncListener}.
 */
public final class TcpSink implements SyncSink {

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

    /** @return the actual bound inbound port once started (0 = ephemeral). */
    public int inboundPort() {
        return server != null ? server.getLocalPort() : localPort;
    }

    @Override
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        server = new ServerSocket(localPort);
        acceptThread = new Thread(this::acceptLoop, "tcp-sink-inbound");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public void stop() {
        running.set(false);
        try {
            if (server != null) {
                server.close();
            }
            if (pendingSocket != null) {
                pendingSocket.close();
            }
        } catch (IOException e) {
            // best effort: the accept loop is daemon and exits on running=false
        }
        server = null;
    }

    @Override
    public void send(Message message) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(remoteHost, remotePort));
            Writer writer = new OutputStreamWriter(socket.getOutputStream(),
                StandardCharsets.UTF_8);
            writer.write(MessageCodec.toJson(message));
            writer.write("\n");
            writer.flush();
        }
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    private void acceptLoop() {
        while (running.get()) {
            try (Socket socket = server.accept()) {
                pendingSocket = socket;
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line == null || line.isBlank()) {
                    continue;
                }
                SyncListener current = listener;
                if (current != null) {
                    current.onMessage(this, MessageCodec.fromJson(line));
                }
            } catch (IOException e) {
                if (running.get()) {
                    notifyDisconnect(e.getMessage());
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
}