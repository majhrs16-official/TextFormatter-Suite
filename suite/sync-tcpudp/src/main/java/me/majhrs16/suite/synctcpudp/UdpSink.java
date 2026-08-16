package me.majhrs16.suite.synctcpudp;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw UDP edge: outbound sends one datagram per message to a remote listener;
 * inbound binds a local port, reassembles line-terminated datagrams and feeds
 * the decoder through {@link SyncListener}.
 */
public final class UdpSink implements SyncSink {

    private static final int MAX_DATAGRAM = 65_507;

    private final String remoteHost;
    private final int remotePort;
    private final int localPort;
    private volatile SyncListener listener;
    private final AtomicBoolean running = new AtomicBoolean();
    private DatagramSocket socket;
    private Thread receiveThread;

    public UdpSink(String remoteHost, int remotePort, int localPort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
    }

    @Override
    public String name() {
        return "udp";
    }

    /** @return the actual bound inbound port once started (0 = ephemeral). */
    public int inboundPort() {
        return socket != null ? socket.getLocalPort() : localPort;
    }

    @Override
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        socket = new DatagramSocket(localPort);
        receiveThread = new Thread(this::receiveLoop, "udp-sink-inbound");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    @Override
    public void stop() {
        running.set(false);
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
    }

    @Override
    public void send(Message message) throws IOException {
        byte[] payload = MessageCodec.toJson(message).getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, payload.length,
            new InetSocketAddress(remoteHost, remotePort));
        socket.send(packet);
    }

    @Override
    public void setListener(SyncListener listener) {
        this.listener = listener;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                DatagramSocket current = socket;
                if (current == null) {
                    return;
                }
                current.receive(packet);
                String line = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8);
                if (line.isBlank()) {
                    continue;
                }
                SyncListener currentListener = listener;
                if (currentListener != null) {
                    currentListener.onMessage(this, MessageCodec.fromJson(line));
                }
            } catch (IOException e) {
                if (running.get()) {
                    SyncListener currentListener = listener;
                    if (currentListener != null) {
                        currentListener.onDisconnect(this, e.getMessage());
                    }
                }
            }
        }
    }
}