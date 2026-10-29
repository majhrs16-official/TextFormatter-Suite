package me.majhrs16.suite.syncvelocity;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.velocity.VelocityConnection;
import me.majhrs16.suite.api.velocity.VelocitySettings;
import me.majhrs16.suite.transport.MessageCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Neutral Velocity edge connector.
 *
 * <p>Owns the queue, retry policy, mapping, metrics and payload codec. The
 * Velocity API lives behind {@link VelocityConnection}; this class never
 * imports {@code com.velocitypowered}.</p>
 */
public final class VelocitySink implements SyncSink {

    private final VelocityConnection connection;
    private final VelocityConfig config;
    private final VelocitySettings settings;
    private final PluginLogger logger;

    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    private final AtomicLong messagesRetried = new AtomicLong(0);
    private final AtomicLong lastSendTime = new AtomicLong(0);
    private final AtomicReference<Instant> lastErrorTime = new AtomicReference<>();

    private final ConcurrentLinkedQueue<QueuedMessage> sendQueue = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService retryExecutor;
    private ScheduledExecutorService metricsExecutor;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private boolean transportStarted;
    private final AtomicReference<SyncListener> listenerRef = new AtomicReference<>();
    private final Map<Pattern, String> compiledMappings = new ConcurrentHashMap<>();

    public VelocitySink(VelocityConnection connection, VelocityConfig config,
                        PluginLogger logger) {
        this(connection, config, VelocitySettings.defaults(), logger);
    }

    public VelocitySink(VelocityConnection connection, VelocityConfig config,
                        VelocitySettings settings, PluginLogger logger) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.config = Objects.requireNonNull(config, "config");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
        config.validate();
        compileMappings();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void compileMappings() {
        compiledMappings.clear();
        if (config.mapping == null || config.mapping.isBlank()) {
            return;
        }
        for (String raw : config.mapping.split(";")) {
            String mapping = raw.trim();
            if (mapping.isEmpty()) {
                continue;
            }
            String[] parts = mapping.split("->", 2);
            if (parts.length != 2) {
                logger.warn("Invalid Velocity mapping (no '->'): " + mapping);
                continue;
            }
            String source = parts[0].trim();
            String target = parts[1].trim();
            Pattern pattern;
            if (source.startsWith("regex:")) {
                pattern = Pattern.compile(source.substring(6));
            } else if (source.startsWith("type:")) {
                compiledMappings.put(
                    Pattern.compile("__TYPE__:" + Pattern.quote(source.substring(5))), target);
                continue;
            } else if ("*".equals(source)) {
                pattern = Pattern.compile(".*");
            } else {
                pattern = Pattern.compile(Pattern.quote(source));
            }
            compiledMappings.put(pattern, target);
        }
    }

    @Override
    public String name() {
        return "velocity";
    }

    @Override
    public DeliverySemantics deliverySemantics() {
        return DeliverySemantics.AT_LEAST_ONCE;
    }

    @Override
    public Ordering ordering() {
        return Ordering.GLOBAL;
    }

    @Override
    public synchronized void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            logger.warn("VelocitySink already started");
            return;
        }
        if (!config.enabled) {
            logger.info("VelocitySink disabled by config");
            return;
        }
        try {
            connection.start(this::handleInbound);
            transportStarted = true;

            retryExecutor = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemon(runnable, "velocity-sink-retry"));
            retryExecutor.scheduleAtFixedRate(this::processQueue,
                config.retryInitialDelay.toMillis(),
                config.retryInitialDelay.toMillis(),
                TimeUnit.MILLISECONDS);

            if (config.metricsInterval.toMillis() > 0) {
                metricsExecutor = Executors.newSingleThreadScheduledExecutor(
                    runnable -> daemon(runnable, "velocity-sink-metrics"));
                metricsExecutor.scheduleAtFixedRate(this::logMetrics,
                    config.metricsInterval.toMillis(),
                    config.metricsInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
            }
        } catch (Exception failure) {
            started.set(false);
            if (transportStarted) {
                connection.stop();
                transportStarted = false;
            }
            shutdownExecutors();
            throw failure;
        }
        logger.info("VelocitySink started on channel " + settings.channel()
            + " (enabled=" + config.enabled + ", dynamicDiscovery=" + config.dynamicDiscovery + ")");
    }

    @Override
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        shuttingDown.set(true);
        logger.info("VelocitySink stopping, draining queue...");

        Instant deadline = Instant.now().plus(config.queueDrainTimeout);
        while (!sendQueue.isEmpty() && Instant.now().isBefore(deadline)) {
            processQueue();
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!sendQueue.isEmpty()) {
            logger.warn("Velocity queue not fully drained on shutdown: " + sendQueue.size());
        }

        if (transportStarted) {
            connection.stop();
            transportStarted = false;
        }
        shutdownExecutors();
        logger.info("VelocitySink stopped");
    }

    @Override
    public void send(Message message) throws IOException {
        if (!config.enabled) {
            return;
        }
        if (!started.get()) {
            throw new IOException("Velocity sink is not started");
        }
        if (shuttingDown.get()) {
            throw new IOException("Velocity sink is shutting down");
        }

        byte[] data = encode(message);
        List<String> targets = resolveTargets(message.channel(), message.type().name());
        if (targets.isEmpty()) {
            logger.debug("No Velocity target servers for channel '" + message.channel()
                + "', type '" + message.type() + "'");
            return;
        }
        for (String server : targets) {
            sendAsync(server, data);
        }
    }

    private byte[] encode(Message message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(settings.subchannel());
            out.writeUTF(message.type().name());
            out.writeUTF(message.id().toString());
            out.writeUTF(message.sender() != null ? message.sender().name() : "CONSOLE");
            out.writeUTF(message.channel() == null ? "" : message.channel());
            out.writeUTF(MessageCodec.toJsonString(message));
            out.writeUTF(message.sender() != null ? message.sender().uuid().toString() : "");
            out.writeLong(System.currentTimeMillis());
            if (config.secret != null && !config.secret.isBlank()) {
                out.writeUTF(config.secret);
            }
        }
        return bytes.toByteArray();
    }

    private List<String> resolveTargets(String channel, String messageType) {
        List<String> available = connection.servers();
        List<String> targets = new ArrayList<>();

        for (Map.Entry<Pattern, String> entry : compiledMappings.entrySet()) {
            Pattern pattern = entry.getKey();
            String target = entry.getValue();
            boolean matches;
            if (pattern.pattern().startsWith("__TYPE__:")) {
                String typePattern = pattern.pattern().substring("__TYPE__:".length());
                matches = Pattern.matches(typePattern, messageType);
            } else {
                matches = pattern.matcher(channel == null ? "" : channel).matches();
            }
            if (!matches) {
                continue;
            }
            addTarget(target, available, targets);
        }

        if (targets.isEmpty()) {
            if (!config.servers.isEmpty()) {
                for (String name : config.servers) {
                    if (available.contains(name)) {
                        targets.add(name);
                    }
                }
            } else if (config.dynamicDiscovery) {
                targets.addAll(available);
            }
        }
        return targets;
    }

    private void addTarget(String target, List<String> available, List<String> targets) {
        if ("*".equals(target) || "all".equalsIgnoreCase(target)) {
            if (!config.servers.isEmpty()) {
                for (String name : config.servers) {
                    if (available.contains(name) && !targets.contains(name)) {
                        targets.add(name);
                    }
                }
            } else if (config.dynamicDiscovery) {
                for (String name : available) {
                    if (!targets.contains(name)) {
                        targets.add(name);
                    }
                }
            }
        } else if (available.contains(target) && !targets.contains(target)) {
            targets.add(target);
        }
    }

    private void sendAsync(String server, byte[] data) {
        if (shuttingDown.get()) {
            queueForRetry(server, data, 0);
            return;
        }
        if (connection.send(server, data)) {
            metricsRecordSent();
            return;
        }
        queueForRetry(server, data, 0);
    }

    private void queueForRetry(String server, byte[] data, int retryCount) {
        if (sendQueue.size() >= config.maxQueueSize) {
            metricsRecordFailed();
            logger.warn("Velocity send queue full (" + config.maxQueueSize
                + "), dropping message to " + server);
            return;
        }
        Duration delay = calculateBackoff(retryCount);
        sendQueue.offer(new QueuedMessage(data, server, retryCount, Instant.now().plus(delay)));
        logger.debug("Queued Velocity message for retry " + retryCount + " to " + server
            + " (delay: " + delay + ")");
    }

    private Duration calculateBackoff(int retryCount) {
        if (retryCount >= config.maxRetries) {
            return config.retryMaxDelay;
        }
        long delay = (long) (config.retryInitialDelay.toMillis()
            * Math.pow(config.retryMultiplier, retryCount));
        return Duration.ofMillis(Math.min(delay, config.retryMaxDelay.toMillis()));
    }

    private void processQueue() {
        if (shuttingDown.get() && sendQueue.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        QueuedMessage queued = sendQueue.poll();
        while (queued != null) {
            if (queued.nextRetry.isAfter(now)) {
                sendQueue.offer(queued);
                break;
            }
            if (queued.retryCount >= config.maxRetries) {
                metricsRecordFailed();
                logger.error("Velocity max retries exceeded for server " + queued.server
                    + ", dropping message");
                queued = sendQueue.poll();
                continue;
            }
            if (connection.send(queued.server, queued.data)) {
                metricsRecordSent();
                metricsRecordRetried();
            } else {
                Duration delay = calculateBackoff(queued.retryCount + 1);
                sendQueue.offer(new QueuedMessage(queued.data, queued.server,
                    queued.retryCount + 1, Instant.now().plus(delay)));
                metricsRecordRetried();
            }
            queued = sendQueue.poll();
        }
    }

    @Override
    public void setListener(SyncListener listener) {
        listenerRef.set(listener);
    }

    private void shutdownExecutors() {
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
            retryExecutor = null;
        }
        if (metricsExecutor != null) {
            metricsExecutor.shutdownNow();
            metricsExecutor = null;
        }
    }

    private void handleInbound(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            String subChannel = in.readUTF();
            if (!settings.subchannel().equals(subChannel)) {
                return;
            }
            in.readUTF(); // type
            in.readUTF(); // id
            in.readUTF(); // sender name
            in.readUTF(); // channel
            String json = in.readUTF();
            in.readUTF(); // sender uuid
            in.readLong(); // timestamp
            if (config.secret != null && !config.secret.isBlank()) {
                String auth = in.readUTF();
                if (!config.secret.equals(auth)) {
                    logger.warn("Velocity auth failed for inbound message");
                    return;
                }
            }
            Message message = MessageCodec.fromJson(json);
            metricsRecordReceived();
            SyncListener current = listenerRef.get();
            if (current != null) {
                current.onMessage(this, message);
            }
        } catch (Exception failure) {
            logger.error("Failed to parse inbound Velocity message", failure);
            lastErrorTime.set(Instant.now());
        }
    }

    private void metricsRecordSent() {
        messagesSent.incrementAndGet();
        lastSendTime.set(System.currentTimeMillis());
    }

    private void metricsRecordReceived() {
        messagesReceived.incrementAndGet();
    }

    private void metricsRecordFailed() {
        messagesFailed.incrementAndGet();
        lastErrorTime.set(Instant.now());
    }

    private void metricsRecordRetried() {
        messagesRetried.incrementAndGet();
    }

    private void logMetrics() {
        logger.info("VelocitySink metrics: sent=" + messagesSent.get()
            + ", received=" + messagesReceived.get()
            + ", failed=" + messagesFailed.get()
            + ", retried=" + messagesRetried.get()
            + ", queue=" + sendQueue.size());
    }

    public HealthStatus getHealth() {
        boolean healthy = started.get() && config.enabled && !shuttingDown.get();
        String status = healthy ? "UP" : "DOWN";
        String detail = healthy ? "Running" : (shuttingDown.get() ? "Shutting down" : "Disabled");
        if (!sendQueue.isEmpty()) {
            detail += " (queue: " + sendQueue.size() + ")";
        }
        return new HealthStatus(status, detail, Map.of(
            "messagesSent", messagesSent.get(),
            "messagesReceived", messagesReceived.get(),
            "messagesFailed", messagesFailed.get(),
            "messagesRetried", messagesRetried.get(),
            "queueSize", sendQueue.size(),
            "lastSendTime", lastSendTime.get() > 0
                ? Instant.ofEpochMilli(lastSendTime.get()).toString() : "never",
            "lastErrorTime", lastErrorTime.get() != null
                ? lastErrorTime.get().toString() : "never"
        ));
    }

    public VelocityConfig getConfig() {
        return config;
    }

    public long getMessagesSent() {
        return messagesSent.get();
    }

    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getMessagesFailed() {
        return messagesFailed.get();
    }

    public long getQueueSize() {
        return sendQueue.size();
    }

    public static final class QueuedMessage {
        final byte[] data;
        final String server;
        final int retryCount;
        final Instant nextRetry;
        final Instant created;

        QueuedMessage(byte[] data, String server, int retryCount, Instant nextRetry) {
            this.data = data;
            this.server = server;
            this.retryCount = retryCount;
            this.nextRetry = nextRetry;
            this.created = Instant.now();
        }
    }

    public static final class HealthStatus {
        private final String status;
        private final String detail;
        private final Map<String, Object> metrics;

        HealthStatus(String status, String detail, Map<String, Object> metrics) {
            this.status = status;
            this.detail = detail;
            this.metrics = metrics;
        }

        public String status() {
            return status;
        }

        public String detail() {
            return detail;
        }

        public Map<String, Object> metrics() {
            return metrics;
        }
    }
}
