package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles in-world interactions: signs, chests, books, and click/hover events.
 */
public final class InWorldHandler implements Listener {

    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final ChannelRegistry channels;
    private final PluginLogger logger;
    private final TranslationService translation;
    private final UserLanguageStore languages;

    // Sign cache
    private final Map<Location, SignData> signCache = new ConcurrentHashMap<>();
    
    // Chest/book cache
    private final Map<Location, ContainerData> containerCache = new ConcurrentHashMap<>();
    
    // Glossary cache
    private final Map<String, String> glossary = new ConcurrentHashMap<>();

    // RADIUS channel cache
    private final Map<UUID, RadiusData> radiusCache = new ConcurrentHashMap<>();

    public InWorldHandler(SuiteHost host, MessageDispatcher dispatcher,
                          ChannelRegistry channels, PluginLogger logger,
                          TranslationService translation, UserLanguageStore languages) {
        this.host = host;
        this.dispatcher = dispatcher;
        this.channels = channels;
        this.logger = logger;
        this.translation = translation;
        this.languages = languages;
    }

    // ============================================================
    // Sign Handling
    // ============================================================

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Sign sign = (Sign) event.getBlock().getState();
        Location loc = sign.getLocation();
        
        // Check if this sign is managed by the suite
        if (!isSuiteSign(loc)) return;

        // Get the channel associated with this sign
        String channelName = getSignChannel(loc);
        if (channelName == null) return;

        Channel channel = channels.resolve(channelName);
        if (channel == null) return;

        // Process each line through the formatter
        String[] lines = event.getLines();
        for (int i = 0; i < lines.length; i++) {
            String original = lines[i];
            if (original == null || original.isBlank()) continue;

            // Create message for formatting
            Actor actor = new Actor(player.getUniqueId(), player.getName(),
                Actor.ActorKind.PLAYER, getPlayerLanguage(player), player);
            
            Message message = Message.builder()
                .type(MessageType.SIGN)
                .sender(actor)
                .direction(Direction.all())
                .translate(true)
                .text(original)
                .channel(channelName)
                .build();

            // Dispatch through the pipeline
            dispatcher.dispatch(message);

            // Get formatted result
            String formatted = getFormattedMessage(message, original);
            event.setLine(i, formatted);
        }

        // Cache sign data
        signCache.put(loc, new SignData(channelName, event.getLines()));
        logger.debug("Suite sign updated at " + loc + " for channel " + channelName);
    }

    /**
     * Checks if a sign at location is managed by the suite.
     */
    private boolean isSuiteSign(Location loc) {
        // Check if there's a sign configuration for this location
        // Could be based on a config file or a marker
        return signCache.containsKey(loc) || isConfiguredSign(loc);
    }

    private boolean isConfiguredSign(Location loc) {
        // Check config for sign locations
        // For now, check if there's a sign channel configured
        return channels.paths().stream()
            .anyMatch(name -> name.startsWith("sign."));
    }

    private String getSignChannel(Location loc) {
        // Find which sign channel this location belongs to
        for (String name : channels.paths()) {
            if (name.startsWith("sign.")) {
                Channel ch = channels.resolve(name);
                // Check if this location matches the sign's configured location
                // This would be stored in the channel config or signCache
            }
        }
        return null;
    }

    private String getPlayerLanguage(Player player) {
        return languages.languageOf(player.getUniqueId())
            .orElse(languages.languageOf(player.getUniqueId()).orElse("auto"));
    }

    private String getFormattedMessage(Message message, String original) {
        // This would use the host's renderer to format
        // For now return original
        return original;
    }

    // ============================================================
    // Chest / Container Interactions
    // ============================================================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        Block block = event.getClickedBlock();
        if (block == null) return;

        BlockData data = block.getBlockData();
        String material = data.getMaterial().name();

        // Check for chest interaction
        if (material.contains("CHEST")) {
            handleChestOpen(event.getPlayer(), block.getLocation());
        }

        // Check for barrel, shulker box, etc.
        if (material.contains("BARREL") || material.contains("SHULKER")) {
            handleContainerOpen(event.getPlayer(), block.getLocation());
        }

        // Check for book interaction (written book in hand)
        if (event.getItem() != null && event.getItem().getType().name().contains("WRITTEN_BOOK")) {
            handleBookRead(event.getPlayer(), event.getItem());
        }
    }

    private void handleChestOpen(Player player, Location loc) {
        String channelName = "container.chest";
        if (!channels.paths().contains(channelName)) return;

        Channel channel = channels.resolve(channelName);
        if (channel == null) return;

        Actor actor = new Actor(player.getUniqueId(), player.getName(),
            Actor.ActorKind.PLAYER, getPlayerLanguage(player), player);

        String content = "Chest opened at " + loc.getBlockX() + ", " + 
                         loc.getBlockY() + ", " + loc.getBlockZ();

        Message message = Message.builder()
            .type(MessageType.CONTAINER)
            .sender(actor)
            .direction(Direction.all())
            .translate(true)
            .text(content)
            .channel(channelName)
            .build();

        dispatcher.dispatch(message);
        containerCache.put(loc, new ContainerData(channelName, System.currentTimeMillis()));
    }

    private void handleContainerOpen(Player player, Location loc) {
        // Similar to chest but for other container types
        handleChestOpen(player, loc);
    }

    private void handleBookRead(Player player, ItemStack book) {
        if (!book.hasItemMeta()) return;
        ItemMeta meta = book.getItemMeta();
        if (!(meta instanceof BookMeta bookMeta)) return;

        String channelName = "book.read";
        if (!channels.paths().contains(channelName)) return;

        Channel channel = channels.resolve(channelName);
        if (channel == null) return;

        Actor actor = new Actor(player.getUniqueId(), player.getName(),
            Actor.ActorKind.PLAYER, getPlayerLanguage(player), player);

        String content = "Book read: " + bookMeta.getTitle();
        for (String page : bookMeta.getPages()) {
            content += "\n" + page;
        }

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

    /**
     * Gets players within a radius for RADIUS channels.
     */
    public List<Actor> getPlayersInRadius(Location center, double radius) {
        List<Actor> players = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= radius * radius) {
                players.add(new Actor(p.getUniqueId(), p.getName(),
                    Actor.ActorKind.PLAYER, getPlayerLanguage(p), p));
            }
        }
        return players;
    }

    /**
     * Gets players in a specific world for WORLD channels.
     */
    public List<Actor> getPlayersInWorld(String worldName) {
        List<Actor> players = new ArrayList<>();
        for (Player p : host.getServer().getWorld(worldName).getPlayers()) {
            players.add(new Actor(p.getUniqueId(), p.getName(),
                Actor.ActorKind.PLAYER, getPlayerLanguage(p), p));
        }
        return players;
    }

    // ============================================================
    // Click / Hover Interactions
    // ============================================================

    /**
     * Handles click events on items with hover text.
     */
    public void handleClick(Actor actor, String action, String value) {
        // Handle click events like opening URLs, running commands, etc.
        switch (action) {
            case "open_url" -> openUrl(actor, value);
            case "run_command" -> runCommand(actor, value);
            case "suggest_command" -> suggestCommand(actor, value);
            case "change_page" -> changePage(actor, value);
            case "copy_to_clipboard" -> copyToClipboard(actor, value);
        }
    }

    /**
     * Handles hover events to show additional information.
     */
    public String getHoverText(Actor actor, String key) {
        // Return glossary entry, translation preview, etc.
        return glossary.getOrDefault(key, "");
    }

    private void openUrl(Actor actor, String url) {
        // Send clickable URL to player
        if (actor.handle() instanceof Player player) {
            player.sendMessage("<click:open_url:'" + url + "'><hover:show_text:'Click to open'>" + url + "</hover></click>");
        }
    }

    private void runCommand(Actor actor, String command) {
        if (actor.handle() instanceof Player player) {
            player.performCommand(command);
        }
    }

    private void suggestCommand(Actor actor, String command) {
        if (actor.handle() instanceof Player player) {
            player.sendMessage("<click:suggest_command:'" + command + "'><hover:show_text:'Click to suggest'>" + command + "</hover></click>");
        }
    }

    private void changePage(Actor actor, String page) {
        // For book/page navigation
    }

    private void copyToClipboard(Actor actor, String text) {
        if (actor.handle() instanceof Player player) {
            player.sendMessage("<click:copy_to_clipboard:'" + text + "'><hover:show_text:'Click to copy'>" + text + "</hover></click>");
        }
    }

    // ============================================================
    // Glossary / Cache
    // ============================================================

    /**
     * Adds a glossary entry for hover tooltips.
     */
    public void addGlossaryEntry(String key, String definition) {
        glossary.put(key, definition);
    }

    /**
     * Gets a glossary entry for hover display.
     */
    public String getGlossaryEntry(String key) {
        return glossary.get(key);
    }

    /**
     * Clears the glossary cache.
     */
    public void clearGlossary() {
        glossary.clear();
    }

    // ============================================================
    // RADIUS Channel Management
    // ============================================================

    public void updateRadiusCache(UUID playerId, Location location, double radius) {
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
        final Location center;
        final double radius;
        final long timestamp;

        RadiusData(Location center, double radius, long timestamp) {
            this.center = center;
            this.radius = radius;
            this.timestamp = timestamp;
        }
    }
}