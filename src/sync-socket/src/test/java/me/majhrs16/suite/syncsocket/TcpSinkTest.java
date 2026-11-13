package me.majhrs16.suite.syncsocket;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSinkTest {

    private static final Principal PROGRAM =
        new Principal.Program("0123456789abcdef".repeat(4));

    private static SyncCall nativeCall(String action) {
        return new SyncCall("tcp", action, Map.of(), PROGRAM, "", "test");
    }

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
            try {
                sink.start();
                sink.send(Message.builder().sender(Actor.unknown("Steve"))
                    .text("hola").channel("chat").build());
                serverThread.join(2000);

                assertTrue(received[0] != null && received[0].contains("\"texts\":[\"hola\"]"));
            } finally {
                sink.stop();
            }
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

    @Test
    void publishesLeastCommonMultipleAndNativeActions() {
        TcpSink sink = new TcpSink("localhost", 1, 0);

        SyncSurface surface = sink.surface();

        assertEquals("tcp", surface.backend());
        assertTrue(surface.hasLeastCommonMultiple());
        assertEquals(ActionKind.READ,
            surface.action("endpoint.info").orElseThrow().kind());
        assertEquals(ActionKind.READ,
            surface.action("connection.probe").orElseThrow().kind());
    }

    @Test
    void endpointInfoDescribesSocketWithoutSecrets() throws Exception {
        TcpSink sink = new TcpSink("sync.example", 7000, 0);
        try {
            sink.start();

            SyncResult result = sink.invoke(nativeCall("endpoint.info"));

            String info = String.valueOf(
                assertInstanceOf(SyncResult.Ok.class, result).value());
            assertTrue(info.contains("name=tcp"), info);
            assertTrue(info.contains("running=true"), info);
            assertTrue(info.contains("remoteHost=sync.example"), info);
            assertTrue(info.contains("remotePort=7000"), info);
            assertTrue(info.contains("inboundListening=true"), info);
        } finally {
            sink.stop();
        }
    }

    @Test
    void connectionProbeReportsReachableRemote() throws Exception {
        try (ServerSocket remote = new ServerSocket(0)) {
            TcpSink sink = new TcpSink("127.0.0.1", remote.getLocalPort(), 0);
            try {
                sink.start();

                SyncResult result = sink.invoke(nativeCall("connection.probe"));

                String probe = String.valueOf(
                    assertInstanceOf(SyncResult.Ok.class, result).value());
                assertTrue(probe.contains("reachable=true"), probe);
                assertTrue(probe.contains("latencyMillis="), probe);
            } finally {
                sink.stop();
            }
        }
    }

    @Test
    void connectionProbeReportsUnreachableRemote() throws Exception {
        int closedPort;
        try (ServerSocket remote = new ServerSocket(0)) {
            closedPort = remote.getLocalPort();
        }
        TcpSink sink = new TcpSink("127.0.0.1", closedPort, 0);
        try {
            sink.start();

            SyncResult result = sink.invoke(nativeCall("connection.probe"));

            assertInstanceOf(SyncResult.HostFailure.class, result);
        } finally {
            sink.stop();
        }
    }

    @Test
    void officialPrincipalCannotInvokeNativeActions() {
        TcpSink sink = new TcpSink("localhost", 1, 0);

        SyncResult result = sink.invoke(new SyncCall(
            "tcp", "endpoint.info", Map.of(),
            new Principal.Official("sync-bus"), "", "test"));

        assertInstanceOf(SyncResult.Denied.class, result);
    }

    @Test
    void unsupportedActionIsExplicit() {
        TcpSink sink = new TcpSink("localhost", 1, 0);

        SyncResult result = sink.invoke(new SyncCall(
            "tcp", "channel.create", Map.of(), PROGRAM, "", "test"));

        assertInstanceOf(SyncResult.Unsupported.class, result);
    }

    @Test
    void stoppedSinkRejectsNativeActionsAndSend() {
        TcpSink sink = new TcpSink("localhost", 1, 0);

        SyncResult info = sink.invoke(nativeCall("endpoint.info"));
        SyncResult probe = sink.invoke(nativeCall("connection.probe"));

        assertInstanceOf(SyncResult.HostFailure.class, info);
        assertInstanceOf(SyncResult.HostFailure.class, probe);
        assertThrows(IOException.class, () -> sink.send(Message.builder()
            .sender(Actor.unknown("Steve")).text("detenido").channel("chat").build()));
        assertFalse(sink.isRunning());
    }

    @Test
    void restartsInboundListenerAfterStop() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        TcpSink sink = new TcpSink("localhost", 1, 0);
        sink.setListener(new SyncListener() {
            @Override public void onMessage(SyncSink s, Message message) {
                received.add(message);
                delivered.countDown();
            }
            @Override public void onDisconnect(SyncSink s, String reason) { }
        });
        try {
            sink.start();
            sink.stop();
            sink.start();

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", sink.inboundPort()));
                Writer writer = new OutputStreamWriter(socket.getOutputStream(),
                    StandardCharsets.UTF_8);
                writer.write(MessageCodec.toJson(Message.builder()
                    .sender(Actor.unknown("ALEX")).text("reinicio").channel("tcp").build()));
                writer.write("\n");
                writer.flush();
            }

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "message was not delivered");
            assertEquals("reinicio", received.get(0).text());
        } finally {
            sink.stop();
        }
    }
}
