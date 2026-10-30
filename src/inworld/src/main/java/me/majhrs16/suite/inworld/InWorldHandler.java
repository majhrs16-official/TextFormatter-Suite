package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neutral in-world domain: sign/container/book message construction, channel
 * resolution, glossary, radius cache and click dispatch.
 *
 * <p>The class never imports Paper, Bukkit or the host. The platform adapter
 * extracts events, supplies actors and formats the resulting {@link Message};
 * this class only decides what the suite should do.</p>
 */
public final class InWorldHandler {

    private final ChannelRegistry channels;
    private final PluginLogger logger;

    private final Map<LocationKey, SignData> signCache = new ConcurrentHashMap<>();
    private final Map<LocationKey, ContainerData> containerCache = new ConcurrentHashMap<>();
    private final Map<String, String> glossary = new ConcurrentHashMap<>();
    private final Map<UUID, RadiusData> radiusCache = new ConcurrentHashMap<>();

    public InWorldHandler(ChannelRegistry channels, PluginLogger logger) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // ============================================================
    // Sign Handling
    // ============================================================

    /**
     * Resolves the configured suite sign at a block position.
     *
     * @return the channel name, or empty when the position is not a suite sign
     */
    public Optional<String> signChannel(String world, int x, int y, int z) {
        for (String name : channels.paths()) {
            if (!name.startsWith("sign.")) continue;
            Channel channel = channels.resolve(name);
            if (channel == null) continue;
            String signLoc = channel.signLocation();
            if (signLoc == null || signLoc.isBlank()) continue;
            if (matchesLocation(signLoc, world, x, y, z)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    private static boolean matchesLocation(String signLoc, String world,
                                           int x, int y, int z) {
        try {
            String[] parts = signLoc.split(",");
            if (parts.length < 3) return false;
            int sx = Integer.parseInt(parts[0].trim());
            int sy = Integer.parseInt(parts[1].trim());
            int sz = Integer.parseInt(parts[2].trim());
            String sw = parts.length > 3 ? parts[3].trim() : world;
            return sx == x && sy == y && sz == z
                && Objects.equals(sw, world);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** @return whether the position is a cached or configured suite sign. */
    public boolean isSuiteSign(String world, int x, int y, int z) {
        return signCache.containsKey(new LocationKey(world, x, y, z))
            || signChannel(world, x, y, z).isPresent();
    }

    /** Builds the message for one non-empty sign line. */
    public Optional<Message> signMessage(Actor actor, String channelName, String text) {
        if (channelName == null || channelName.isBlank()) return Optional.empty();
        if (text == null || text.isBlank()) return Optional.empty();
        if (!hasChannel(channelName)) return Optional.empty();
        return Optional.of(Message.builder()
            .type(MessageType.SIGN)
            .sender(actor)
            .direction(Direction.all())
            .translate(true)
            .text(text)
            .channel(channelName)
            .build());
    }

    /** Stores the last rendered sign content for diagnostics and reuse. */
    public void cacheSign(String world, int x, int y, int z,
                          String channelName, String[] lines) {
        signCache.put(new LocationKey(world, x, y, z),
            new SignData(channelName, lines, System.currentTimeMillis()));
    }

    // ============================================================
    // Chest / Container / Book Interactions
    // ============================================================

    /** Builds the message emitted when a player opens a container. */
    public Optional<Message> containerMessage(Actor actor, String channelName,
                                              String world, int x, int y, int z) {
        if (!hasChannel(channelName)) return Optional.empty();
        String content = "Chest opened at " + x + ", " + y + ", " + z;
        containerCache.put(new LocationKey(world, x, y, z),
            new ContainerData(channelName, System.currentTimeMillis()));
        return Optional.of(Message.builder()
            .type(MessageType.CONTAINER)
            .sender(actor)
            .direction(Direction.all())
            .translate(true)
            .text(content)
            .channel(channelName)
            .build());
    }

    /** Builds the message emitted when a player reads a written book. */
    public Optional<Message> bookMessage(Actor actor, String channelName,
                                         String title, List<String> pages) {
        if (!hasChannel(channelName)) return Optional.empty();
        StringBuilder content = new StringBuilder("Book read: ")
            .append(title == null ? "" : title);
        if (pages != null) {
            for (String page : pages) {
                content.append('\n').append(page == null ? "" : page);
            }
        }
        return Optional.of(Message.builder()
            .type(MessageType.BOOK)
            .sender(actor)
            .direction(Direction.all())
            .translate(true)
            .text(content.toString())
            .channel(channelName)
            .build());
    }

    // ============================================================
    // WORLD / RADIUS Channels
    // ============================================================

    public List<Actor> getPlayersInRadius(InWorldPlatform platform, String world,
                                          double x, double y, double z,
                                          double radius) {
        Objects.requireNonNull(platform, "platform");
        return platform.playersInRadius(world, x, y, z, radius);
    }

    public List<Actor> getPlayersInWorld(InWorldPlatform platform, String world) {
        Objects.requireNonNull(platform, "platform");
        return platform.playersInWorld(world);
    }

    // ============================================================
    // Click / Hover Interactions
    // ============================================================

    /** Delegates a click action to the platform adapter. */
    public void handleClick(InWorldPlatform platform, Actor actor,
                            String action, String value) {
        Objects.requireNonNull(platform, "platform");
        switch (action) {
            case "open_url" -> platform.openUrl(actor, value);
            case "run_command" -> platform.runCommand(actor, value);
            case "suggest_command" -> platform.suggestCommand(actor, value);
            case "change_page" -> platform.changePage(actor, value);
            case "copy_to_clipboard" -> platform.copyToClipboard(actor, value);
            default -> logger.debug("Unknown in-world click action: " + action);
        }
    }

    public String getHoverText(Actor actor, String key) {
        return glossary.getOrDefault(key, "");
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

    public void updateRadiusCache(UUID playerId, String world,
                                  double x, double y, double z, double radius) {
        radiusCache.put(playerId, new RadiusData(
            world, x, y, z, radius, System.currentTimeMillis()));
    }

    public Optional<RadiusData> getRadiusData(UUID playerId) {
        return Optional.ofNullable(radiusCache.get(playerId));
    }

    private boolean hasChannel(String name) {
        return name != null && channels.paths().contains(name);
    }

    // ============================================================
    // Data Classes
    // ============================================================

    public record SignData(String channelName, String[] lines, long timestamp) {
        public SignData {
            lines = lines == null ? new String[0] : lines.clone();
        }
    }

    public record ContainerData(String channelName, long timestamp) {
    }

    public record RadiusData(String world, double x, double y, double z,
                             double radius, long timestamp) {
    }

    private record LocationKey(String world, int x, int y, int z) {
    }
}
