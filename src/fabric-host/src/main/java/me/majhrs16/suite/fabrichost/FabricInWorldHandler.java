package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Fabric implementation of in-world interactions handler.
 * Handles signs, containers, books, and click/hover events using Fabric APIs.
 */
public final class FabricInWorldHandler {

    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final ChannelRegistry channels;
    private final PluginLogger logger;
    private final TranslationService translation;
    private final UserLanguageStore languages;
    private final MinecraftServer server;

    // Sign cache
    private final Map<BlockPos, SignData> signCache = new ConcurrentHashMap<>();
    
    // Chest/book cache
    private final Map<BlockPos, ContainerData> containerCache = new ConcurrentHashMap<>();
    
    // Glossary cache
    private final Map<String, String> glossary = new ConcurrentHashMap<>();

    // RADIUS channel cache
    private final Map<UUID, RadiusData> radiusCache = new ConcurrentHashMap<>();

    public FabricInWorldHandler(SuiteHost host, MessageDispatcher dispatcher,
                                MinecraftServer server,
                                ChannelRegistry channels, PluginLogger logger,
                                TranslationService translation, UserLanguageStore languages) {
        this.host = host;
        this.server = server;
        this.dispatcher = dispatcher;
        this.channels = channels;
        this.logger = logger;
        this.translation = translation;
        this.languages = languages;
    }

    /**
     * Register Fabric event listeners for in-world interactions.
     * Should be called during mod initialization.
     */
    public void registerListeners() {
        // Sign change events
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
            // We'll handle sign editing differently in Fabric
            return net.minecraft.util.ActionResult.PASS;
        });

        // Block break events for signs
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) return true;
            // Handle sign breaking
            if (signCache.containsKey(pos)) {
                signCache.remove(pos);
                logger.debug("Suite sign broken at " + pos);
            }
            return true;
        });

        // Note: For full sign editing, book reading, chest opening, etc.,
        // we would need to use Fabric's event system or mixins.
        // This is a minimal implementation for compilation.
        logger.info("FabricInWorldHandler registered (minimal implementation)");
    }

    // ============================================================
    // Sign Handling
    // ============================================================

    /**
     * Processes a sign text change. Called from sign editor screen or command.
     */
    public void processSignChange(ServerPlayerEntity player, BlockPos pos, String[] lines) {
        if (!isSuiteSign(pos)) return;

        String channelName = getSignChannel(pos);
        if (channelName == null) return;

        Channel channel = channels.resolve(channelName);
        if (channel == null) return;

        Actor actor = new Actor(player.getUuid(), player.getName().getString(),
            Actor.ActorKind.PLAYER, getPlayerLanguage(player), player);

        for (int i = 0; i < lines.length; i++) {
            String original = lines[i];
            if (original == null || original.isBlank()) continue;

            Message message = Message.builder()
                .type(MessageType.SIGN)
                .sender(actor)
                .direction(Direction.all())
                .translate(true)
                .text(original)
                .channel(channelName)
                .build();

            dispatcher.dispatch(message);

            // Get formatted result (simplified)
            String formatted = getFormattedMessage(message, original);
            lines[i] = formatted;
        }

        signCache.put(pos, new SignData(channelName, lines));
        logger.debug("Suite sign updated at " + pos + " for channel " + channelName);
    }

    private boolean isSuiteSign(BlockPos pos) {
        return signCache.containsKey(pos) || isConfiguredSign(pos);
    }

    private boolean isConfiguredSign(BlockPos pos) {
        return channels.paths().stream()
            .anyMatch(name -> name.startsWith("sign."));
    }

    private String getSignChannel(BlockPos pos) {
        for (String name : channels.paths()) {
            if (name.startsWith("sign.")) {
                Channel ch = channels.resolve(name);
                // Check if this location matches the sign's configured location
                // This would be stored in the channel config or signCache
            }
        }
        return null;
    }

    private Language getPlayerLanguage(ServerPlayerEntity player) {
        Optional<String> langOpt = languages.languageOf(player.getUuid());
        if (langOpt.isPresent()) {
            Optional<Language> lang = Language.of(langOpt.get());
            if (lang.isPresent()) return lang.get();
        }
        return Language.AUTO;
    }

    private String getFormattedMessage(Message message, String original) {
        // This would use the host's renderer to format
        // For now return original
        return original;
    }

    // ============================================================
    // Chest / Container Interactions
    // ============================================================

    public void handleChestOpen(ServerPlayerEntity player, BlockPos pos) {
        String channelName = "container.chest";
        if (!channels.paths().contains(channelName)) return;

        Channel channel = channels.resolve(channelName);
        if (channel == null) return;

        Actor actor = new Actor(player.getUuid(), player.getName().getString(),
            Actor.ActorKind.PLAYER, getPlayerLanguage(player), player);

        String content = "Chest opened at " + pos.getX() + ", " + 
                         pos.getY() + ", " + pos.getZ();

        Message message = Message.builder()
            .type(MessageType.CONTAINER)
            .sender(actor)
            .direction(Direction.all())
            .translate(true)
            .text(content)
            .channel(channelName)
            .build();

        dispatcher.dispatch(message);
        containerCache.put(pos, new ContainerData(channelName, System.currentTimeMillis()));
    }

    public void handleContainerOpen(ServerPlayerEntity player, BlockPos pos) {
        handleChestOpen(player, pos);
    }

    public void handleBookRead(ServerPlayerEntity player, net.minecraft.item.ItemStack book) {
        String channelName = "book.read";
        if (!channels.paths().contains(channelName)) return;

        Channel channel = channels.resolve(channelName);
        if (channel == null) return;

        Actor actor = new Actor(player.getUuid(), player.getName().getString(),
            Actor.ActorKind.PLAYER, getPlayerLanguage(player), player);

        String content = "Book read: " + book.getName().getString();

        Message message = Message.builder()
            .type(MessageType.BOOK)
            .sender(actor)
            .direction(Direction.all())
            .translate(true)
            .text(content)
            .channel(channelName)
            .build();

        dispatcher.dispatch(message);
    }

    // ============================================================
    // WORLD / RADIUS Channels
    // ============================================================

    public List<Actor> getPlayersInRadius(BlockPos center, double radius) {
        List<Actor> players = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player.getBlockPos().isWithinDistance(center, radius)) {
                    players.add(new Actor(player.getUuid(), player.getName().getString(),
                        Actor.ActorKind.PLAYER, getPlayerLanguage(player), player));
                }
            }
        }
        return players;
    }

    public List<Actor> getPlayersInWorld(String worldName) {
        List<Actor> players = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(worldName)) {
                for (ServerPlayerEntity player : world.getPlayers()) {
                    players.add(new Actor(player.getUuid(), player.getName().getString(),
                        Actor.ActorKind.PLAYER, getPlayerLanguage(player), player));
                }
            }
        }
        return players;
    }

    // ============================================================
    // Click / Hover Interactions
    // ============================================================

    public void handleClick(Actor actor, String action, String value) {
        switch (action) {
            case "open_url" -> openUrl(actor, value);
            case "run_command" -> runCommand(actor, value);
            case "suggest_command" -> suggestCommand(actor, value);
            case "change_page" -> changePage(actor, value);
            case "copy_to_clipboard" -> copyToClipboard(actor, value);
        }
    }

    public String getHoverText(Actor actor, String key) {
        return glossary.getOrDefault(key, "");
    }

    private void openUrl(Actor actor, String url) {
        if (actor.handle() instanceof ServerPlayerEntity player) {
            player.sendMessage(Text.literal("<click:open_url:'" + url + "'><hover:show_text:'Click to open'>" + url + "</hover></click>"));
        }
    }

    private void runCommand(Actor actor, String command) {
        if (actor.handle() instanceof ServerPlayerEntity player) {
            // In Fabric, we'd use server.getCommandManager().executeWithPrefix(...)
        }
    }

    private void suggestCommand(Actor actor, String command) {
        if (actor.handle() instanceof ServerPlayerEntity player) {
            player.sendMessage(Text.literal("<click:suggest_command:'" + command + "'><hover:show_text:'Click to suggest'>" + command + "</hover></click>"));
        }
    }

    private void changePage(Actor actor, String page) {
        // For book/page navigation
    }

    private void copyToClipboard(Actor actor, String text) {
        if (actor.handle() instanceof ServerPlayerEntity player) {
            player.sendMessage(Text.literal("<click:copy_to_clipboard:'" + text + "'><hover:show_text:'Click to copy'>" + text + "</hover></click>"));
        }
    }

    // ============================================================
    // Glossary / Cache
    // ============================================================

    public void addGlossaryEntry(String key, String definition) {
        glossary.put(key, definition);
    }

    public String getGlossaryEntry(String key) {
        return glossary.get(key);
    }

    public void clearGlossary() {
        glossary.clear();
    }

    // ============================================================
    // RADIUS Channel Management
    // ============================================================

    public void updateRadiusCache(UUID playerId, BlockPos location, double radius) {
        radiusCache.put(playerId, new RadiusData(location, radius, System.currentTimeMillis()));
    }

    public Optional<RadiusData> getRadiusData(UUID playerId) {
        return Optional.ofNullable(radiusCache.get(playerId));
    }

    // ============================================================
    // Data Classes
    // ============================================================

    private static class SignData {
        final String channelName;
        final String[] lines;
        final long timestamp;

        SignData(String channelName, String[] lines) {
            this.channelName = channelName;
            this.lines = lines;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static class ContainerData {
        final String channelName;
        final long timestamp;

        ContainerData(String channelName, long timestamp) {
            this.channelName = channelName;
            this.timestamp = timestamp;
        }
    }

    private static class RadiusData {
        final BlockPos center;
        final double radius;
        final long timestamp;

        RadiusData(BlockPos center, double radius, long timestamp) {
            this.center = center;
            this.radius = radius;
            this.timestamp = timestamp;
        }
    }
}