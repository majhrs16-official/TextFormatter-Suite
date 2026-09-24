package me.majhrs16.suite.synctcpudp;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSinkTest {

    @Test
    void sendsOneLinePerMessageToRemoteListener() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            int port = listener.getLocalPort();
            String[] received = new String[1];
            Thread serverThread = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    received[0] = reader.readLine();
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            TcpSink sink = new TcpSink("localhost", port, 0);
            sink.send(Message.builder().sender(Actor.unknown("Steve"))
                .text("hola").channel("chat").build());
            serverThread.join(2000);

            assertTrue(received[0] != null && received[0].contains("\"texts\":[\"hola\"]"));
        }
    }

    @Test
    void inboundListenerDeliversDecodedMessage() throws Exception {
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        TcpSink sink = new TcpSink("localhost", 1, 0);
        sink.setListener(new SyncListener() {
            @Override public void onMessage(SyncSink s, Message message) { received.add(message); }
            @Override public void onDisconnect(SyncSink s, String reason) { }
        });
        sink.start();
        int boundPort = sink.inboundPort();
        try {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", boundPort));
                Writer writer = new OutputStreamWriter(socket.getOutputStream(),
                    StandardCharsets.UTF_8);
                writer.write(MessageCodec.toJson(Message.builder()
                    .sender(Actor.unknown("ALEX")).text("desde red").channel("tcp").build()));
                writer.write("\n");
                writer.flush();
            }
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(300);
            assertEquals(1, received.size());
            assertEquals("desde red", received.get(0).text());
            assertEquals("tcp", received.get(0).channel());
        } finally {
            sink.stop();
        }
    }
}