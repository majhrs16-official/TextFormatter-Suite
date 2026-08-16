package me.majhrs16.suite.syncdiscord;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal RFC6455 WebSocket server for loopback tests. It performs the
 * handshake, sends a Gateway {@code Hello}, then on a client {@code Identify}
 * replies {@code READY} plus any queued dispatches; heartbeats get {@code ACK}s.
 */
final class WsServer implements AutoCloseable {

    private static final int FRAME_TEXT = 1;
    private static final int FRAME_CLOSE = 8;

    private final ServerSocket server = new ServerSocket(0);
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<JSONObject> afterReady = new CopyOnWriteArrayList<>();
    private volatile int heartbeats;
    private volatile Socket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile Throwable failure;

    WsServer() throws IOException {
    }

    int port() {
        return server.getLocalPort();
    }

    Throwable failure() {
        return failure;
    }

    List<String> received() {
        return received;
    }

    int heartbeats() {
        return heartbeats;
    }

    void pushAfterReady(JSONObject dispatch) {
        afterReady.add(dispatch);
    }

    void start() {
        Thread thread = new Thread(this::serve, "ws-stub");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve() {
        try {
            socket = server.accept();
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
            socket.setSoTimeout(5000);
            handshake();
            send(FRAME_TEXT, hello().toString().getBytes(StandardCharsets.UTF_8));
            while (true) {
                Frame frame = readFrame();
                if (frame == null || frame.opcode == FRAME_CLOSE) {
                    break;
                }
                if (frame.opcode == FRAME_TEXT) {
                    handleText(new String(frame.payload, StandardCharsets.UTF_8));
                }
            }
        } catch (SocketTimeoutException e) {
            if (failure == null) {
                failure = e;
            }
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            }
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handleText(String text) throws IOException {
        received.add(text);
        JSONObject payload = new JSONObject(text);
        int op = payload.optInt("op", -1);
        if (op == 2) { // Identify
            send(FRAME_TEXT, ready().toString().getBytes(StandardCharsets.UTF_8));
            for (JSONObject dispatch : afterReady) {
                send(FRAME_TEXT, dispatch.toString().getBytes(StandardCharsets.UTF_8));
            }
        } else if (op == 1) { // Heartbeat
            heartbeats++;
            send(FRAME_TEXT, ack().toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JSONObject hello() {
        return new JSONObject().put("op", 10)
            .put("d", new JSONObject().put("heartbeat_interval", 300));
    }

    private static JSONObject ready() {
        return new JSONObject().put("op", 0).put("s", 1).put("t", "READY")
            .put("d", new JSONObject().put("user",
                new JSONObject().put("id", "1").put("username", "SuiteBot")));
    }

    private static JSONObject ack() {
        return new JSONObject().put("op", 11).put("d", JSONObject.NULL);
    }

    private void handshake() throws IOException {
        String header = readHandshake();
        String key = null;
        for (String line : header.split("\r\n")) {
            if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) {
                key = line.substring(18).trim();
            }
        }
        if (key == null) {
            throw new IOException("no Sec-WebSocket-Key in handshake");
        }
        String accept;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            accept = Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n"
            + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String readHandshake() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] last = new byte[4];
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            last[0] = last[1];
            last[1] = last[2];
            last[2] = last[3];
            last[3] = (byte) b;
            if (last[0] == '\r' && last[1] == '\n' && last[2] == '\r' && last[3] == '\n') {
                break;
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        int b1 = in.read();
        if (b1 == -1) {
            return null;
        }
        int opcode = b0 & 0x0F;
        long length = b1 & 0x7F;
        if (length == 126) {
            length = (long) in.read() << 8 | in.read();
        } else if (length == 127) {
            byte[] wide = new byte[8];
            readFully(wide);
            length = 0;
            for (byte value : wide) {
                length = (length << 8) | (value & 0xFF);
            }
        }
        byte[] mask = new byte[4];
        if ((b1 & 0x80) != 0) {
            readFully(mask);
        }
        byte[] payload = new byte[(int) length];
        readFully(payload);
        if ((b1 & 0x80) != 0) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new Frame(opcode, payload);
    }

    private void readFully(byte[] target) throws IOException {
        int read = 0;
        while (read < target.length) {
            int n = in.read(target, read, target.length - read);
            if (n == -1) {
                throw new IOException("stream closed");
            }
            read += n;
        }
    }

    private void send(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else {
            frame.write(126);
            frame.write((payload.length >> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        }
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private record Frame(int opcode, byte[] payload) {
    }
}