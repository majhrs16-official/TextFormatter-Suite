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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpSinkTest {

    private static final Principal PROGRAM =
        new Principal.Program("0123456789abcdef".repeat(4));

    private static SyncCall nativeCall(String action) {
        return new SyncCall("udp", action, Map.of(), PROGRAM, "", "test");
    }

    @Test
    void outboundSendsDatagramToRemoteListener() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0)) {
            int port = receiver.getLocalPort();
            UdpSink sink = new UdpSink("localhost", port, 0);
            sink.start();
            try {
                Message sent = Message.builder().sender(Actor.unknown("Steve"))
                    .direction(Direction.others()).text("hola").channel("udp").build();
                sink.send(sent);

                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiver.setSoTimeout(2000);
                receiver.receive(packet);
                String line = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8);

                Message decoded = MessageCodec.fromJson(line);
                assertEquals("hola", decoded.text());
                assertEquals("udp", decoded.channel());
            } finally {
                sink.stop();
            }
        }
    }

    @Test
    void inboundReceivesAndDeliversMessage() throws Exception {
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        UdpSink sink = new UdpSink("localhost", 1, 0);
        sink.setListener(new SyncListener() {
            @Override public void onMessage(SyncSink s, Message message) { received.add(message); }
            @Override public void onDisconnect(SyncSink s, String reason) { }
        });
        sink.start();
        try {
            byte[] payload = MessageCodec.toJson(Message.builder()
                .sender(Actor.unknown("ALEX")).text("por datagrama").channel("udp").build())
                .getBytes(StandardCharsets.UTF_8);
            try (DatagramSocket sender = new DatagramSocket()) {
                sender.send(new DatagramPacket(payload, payload.length,
                    new InetSocketAddress("localhost", sink.inboundPort())));
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            assertEquals(1, received.size());
            assertEquals("por datagrama", received.get(0).text());
            assertEquals("udp", received.get(0).channel());
        } finally {
            sink.stop();
        }
    }

    @Test
    void publishesLeastCommonMultipleAndEndpointInfo() {
        UdpSink sink = new UdpSink("localhost", 1, 0);

        SyncSurface surface = sink.surface();

        assertEquals("udp", surface.backend());
        assertTrue(surface.hasLeastCommonMultiple());
        assertEquals(ActionKind.READ,
            surface.action("endpoint.info").orElseThrow().kind());
    }

    @Test
    void endpointInfoDescribesSocketWithoutSecrets() throws Exception {
        UdpSink sink = new UdpSink("sync.example", 7000, 0);
        try {
            sink.start();

            SyncResult result = sink.invoke(nativeCall("endpoint.info"));

            String info = String.valueOf(
                assertInstanceOf(SyncResult.Ok.class, result).value());
            assertTrue(info.contains("name=udp"), info);
            assertTrue(info.contains("running=true"), info);
            assertTrue(info.contains("remoteHost=sync.example"), info);
            assertTrue(info.contains("remotePort=7000"), info);
            assertTrue(info.contains("inboundListening=true"), info);
        } finally {
            sink.stop();
        }
    }

    @Test
    void officialPrincipalCannotInvokeNativeActions() {
        UdpSink sink = new UdpSink("localhost", 1, 0);

        SyncResult result = sink.invoke(new SyncCall(
            "udp", "endpoint.info", Map.of(),
            new Principal.Official("sync-bus"), "", "test"));

        assertInstanceOf(SyncResult.Denied.class, result);
    }

    @Test
    void unsupportedActionIsExplicit() {
        UdpSink sink = new UdpSink("localhost", 1, 0);

        SyncResult result = sink.invoke(new SyncCall(
            "udp", "channel.create", Map.of(), PROGRAM, "", "test"));

        assertInstanceOf(SyncResult.Unsupported.class, result);
    }

    @Test
    void stoppedSinkRejectsNativeActionsAndSend() {
        UdpSink sink = new UdpSink("localhost", 1, 0);

        SyncResult info = sink.invoke(nativeCall("endpoint.info"));

        assertInstanceOf(SyncResult.HostFailure.class, info);
        assertThrows(IOException.class, () -> sink.send(Message.builder()
            .sender(Actor.unknown("Steve")).text("detenido").channel("udp").build()));
        assertFalse(sink.isRunning());
    }

    @Test
    void restartsInboundListenerAfterStop() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        CopyOnWriteArrayList<Message> received = new CopyOnWriteArrayList<>();
        UdpSink sink = new UdpSink("localhost", 1, 0);
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

            byte[] payload = MessageCodec.toJson(Message.builder()
                .sender(Actor.unknown("ALEX")).text("reinicio").channel("udp").build())
                .getBytes(StandardCharsets.UTF_8);
            try (DatagramSocket sender = new DatagramSocket()) {
                sender.send(new DatagramPacket(payload, payload.length,
                    new InetSocketAddress("localhost", sink.inboundPort())));
            }

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "message was not delivered");
            assertEquals("reinicio", received.get(0).text());
        } finally {
            sink.stop();
        }
    }
}
