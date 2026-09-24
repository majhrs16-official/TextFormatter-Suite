package me.majhrs16.suite.synctcpudp;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UdpSinkTest {

    @Test
    void outboundSendsDatagramToRemoteListener() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0)) {
            int port = receiver.getLocalPort();
            UdpSink sink = new UdpSink("localhost", port, 0);
            sink.start();

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
            sink.stop();
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
}