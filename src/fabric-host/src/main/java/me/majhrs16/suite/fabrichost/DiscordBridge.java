package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.config.MessagesConfig;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Optional Discord↔Minecraft bridge for Fabric.
 */
public final class DiscordBridge {

    private final JDA jda;
    private final GuildMessageChannel channel;
    private final MessageDispatcher dispatcher;
    private final PluginLogger logger;
    private final MessagesConfig messages;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private DiscordBridge(JDA jda, GuildMessageChannel channel,
                          MessageDispatcher dispatcher, PluginLogger logger,
                          MessagesConfig messages) {
        this.jda = jda;
        this.channel = channel;
        this.dispatcher = dispatcher;
        this.logger = logger;
        this.messages = messages;
    }

    public static DiscordBridge create(Path folder, MessageDispatcher dispatcher, PluginLogger logger) {
        Path configPath = folder.resolve("sync/discord.yml");
        if (!Files.exists(configPath)) return null;
        try {
            MessagesConfig syncConfig = MessagesConfig.load(configPath, Map.of());
            String token = syncConfig.format("token");
            String channelStr = syncConfig.format("channel");
            long channelId = channelStr != null && !channelStr.isBlank() ? Long.parseLong(channelStr) : 0;
            if (token == null || token.isBlank() || channelId == 0) return null;

            JDA jda = JDABuilder.createDefault(token)
                .setChunkingFilter(ChunkingFilter.NONE)
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .build()
                .awaitReady();

            GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId);
            if (channel == null) {
                jda.shutdown();
                return null;
            }
            return new DiscordBridge(jda, channel, dispatcher, logger, syncConfig);
        } catch (Exception exception) {
            logger.error("Discord bridge init failed", exception);
            return null;
        }
    }

    public void start() {
        channel.getHistory().retrievePast(20).queue(messages -> {
            for (net.dv8tion.jda.api.entities.Message msg : messages) {
                if (msg.getAuthor().isBot()) continue;
                onDiscordMessage(msg.getContentRaw(), msg.getAuthor().getName());
            }
        });

        jda.addEventListener(new net.dv8tion.jda.api.hooks.ListenerAdapter() {
            @Override
            public void onMessageReceived(net.dv8tion.jda.api.events.message.MessageReceivedEvent event) {
                if (event.getAuthor().isBot() || event.isFromType(net.dv8tion.jda.api.entities.channel.ChannelType.PRIVATE)) return;
                onDiscordMessage(event.getMessage().getContentRaw(), event.getAuthor().getName());
            }
        });

        logger.info("Discord bridge started");
    }

    private void onDiscordMessage(String text, String author) {
        Message message = Message.builder()
            .type(MessageType.CHAT)
            .sender(new me.majhrs16.suite.api.message.Actor(
                java.util.UUID.randomUUID(), author,
                me.majhrs16.suite.api.message.Actor.ActorKind.PLAYER,
                me.majhrs16.suite.api.message.Language.AUTO, null))
            .direction(me.majhrs16.suite.api.message.Direction.all())
            .translate(true)
            .text("[" + author + "] " + text)
            .channel("chat.global")
            .build();
        dispatcher.dispatch(message);
    }

    public void mirror(Message message) {
        if (channel == null) return;
        String format = messages.format("format", "{sender}: {content}");
        String sender = message.sender() == null ? "Console" : message.sender().name();
        String text = format.replace("{sender}", sender).replace("{content}", message.text());
        channel.sendMessage(text).queue();
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            scheduler.shutdownNow();
        }
        if (jda != null) jda.shutdown();
        logger.info("Discord bridge stopped");
    }
}
