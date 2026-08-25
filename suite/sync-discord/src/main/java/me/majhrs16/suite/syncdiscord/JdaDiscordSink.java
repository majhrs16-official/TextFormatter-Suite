package me.majhrs16.suite.syncdiscord;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discord sink backed by JDA (implementation detail behind {@link SyncSink}:
 * the engine never sees JDA types). Replaces the hand-rolled gateway as the
 * recommended implementation — JDA brings reconnect/resume, rate-limit
 * handling and entity caching.
 *
 * <p>Scope (parity-lite): plain-text outbound to one configured channel,
 * inbound MESSAGE_CREATE on that channel forwarded to the single
 * {@link SyncListener}. Embeds / replies→DM / role sync remain future work.
 * The legacy {@link DiscordSink} stays available as a zero-dependency
 * alternative.</p>
 */
public final class JdaDiscordSink implements SyncSink {

    private final String token;
    private final long channelId;

    private volatile JDA jda;
    private volatile TextChannel channel;
    private volatile SyncListener listener;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public JdaDiscordSink(String token, long channelId) {
        this.token = token;
        this.channelId = channelId;
    }

    @Override
    public String name() {
        return "discord";
    }

    @Override
    public synchronized void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            jda = JDABuilder.createLight(token, EnumSet.of(
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.MESSAGE_CONTENT))
                .setChunkingFilter(ChunkingFilter.NONE)
                .addEventListeners(new Inbound())
                .build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (jda.getStatus() != JDA.Status.CONNECTED) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("timeout conectando a Discord");
                }
                Thread.sleep(200);
            }
            channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                throw new IllegalStateException("canal Discord no encontrado: " + channelId);
            }
        } catch (Exception e) {
            started.set(false);
            shutdown();
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        shutdown();
    }

    /** Blocking send: callers run off-main; errors propagate per contract. */
    @Override
    public void send(Message message) throws Exception {
        TextChannel target = channel;
        if (target == null) {
            throw new IllegalStateException("sink no iniciado");
        }
        target.sendMessage(message.text()).complete();
    }

    @Override
    public void setListener(SyncListener syncListener) {
        this.listener = syncListener;
    }

    private void shutdown() {
        JDA current = jda;
        jda = null;
        channel = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    private final class Inbound extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (!started.get()
                || event.getAuthor().isBot()
                || event.getChannel().getIdLong() != channelId) {
                return;
            }
            String content = event.getMessage().getContentDisplay();
            if (content == null || content.isBlank()) {
                return;
            }
            SyncListener sink = listener;
            if (sink == null) {
                return;
            }
            Message incoming = Message.builder()
                .sender(Actor.unknown(event.getAuthor().getEffectiveName()))
                .direction(Direction.others())
                .text(content)
                .channel("discord")
                .build();
            sink.onMessage(JdaDiscordSink.this, incoming);
        }
    }
}
