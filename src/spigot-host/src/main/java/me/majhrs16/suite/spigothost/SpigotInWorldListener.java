package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.inworld.InWorldHandler;
import me.majhrs16.suite.inworld.InWorldPlatform;
import me.majhrs16.suite.textformatter.template.TemplateContext;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spigot adapter for the neutral in-world domain.
 *
 * <p>Extracts Bukkit events, supplies live actors and delegates message
 * construction to {@link InWorldHandler}. Formatting and delivery stay in the
 * host pipeline.</p>
 */
public final class SpigotInWorldListener implements Listener, InWorldPlatform {

    private final InWorldHandler handler;
    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final Server server;
    private final PluginLogger logger;
    private final UserLanguageStore languages;

    public SpigotInWorldListener(InWorldHandler handler,
                                 SuiteHost host,
                                 MessageDispatcher dispatcher,
                                 Server server,
                                 PluginLogger logger,
                                 UserLanguageStore languages) {
        this.handler = handler;
        this.host = host;
        this.dispatcher = dispatcher;
        this.server = server;
        this.logger = logger;
        this.languages = languages;
    }

    // ============================================================
    // Sign Handling
    // ============================================================

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();
        String world = loc.getWorld() == null ? "" : loc.getWorld().getName();

        String channelName = handler.signChannel(world,
            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).orElse(null);
        if (channelName == null) return;

        Actor actor = actor(player);
        String[] lines = event.getLines();
        for (int i = 0; i < lines.length; i++) {
            String original = lines[i];
            Optional<Message> message = handler.signMessage(actor, channelName, original);
            if (message.isEmpty()) continue;
            dispatcher.dispatch(message.get());
            event.setLine(i, format(message.get(), original));
        }

        handler.cacheSign(world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
            channelName, event.getLines());
        logger.debug("Suite sign updated at " + loc + " for channel " + channelName);
    }

    // ============================================================
    // Chest / Container / Book Interactions
    // ============================================================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Location loc = block.getLocation();
        String world = loc.getWorld() == null ? "" : loc.getWorld().getName();
        String material = block.getType().name();
        Actor actor = actor(event.getPlayer());

        if (material.contains("CHEST")
            || material.contains("BARREL")
            || material.contains("SHULKER")) {
            handler.containerMessage(actor, "container.chest", world,
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                .ifPresent(dispatcher::dispatch);
        }

        ItemStack item = event.getItem();
        if (item != null && item.getType().name().contains("WRITTEN_BOOK")
            && item.getItemMeta() instanceof BookMeta bookMeta) {
            handler.bookMessage(actor, "book.read", bookMeta.getTitle(), bookMeta.getPages())
                .ifPresent(dispatcher::dispatch);
        }
    }

    // ============================================================
    // WORLD / RADIUS Channels
    // ============================================================

    @Override
    public List<Actor> playersInWorld(String worldName) {
        World world = server.getWorld(worldName);
        if (world == null) return List.of();
        List<Actor> players = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            players.add(actor(player));
        }
        return players;
    }

    @Override
    public List<Actor> playersInRadius(String worldName, double x, double y,
                                       double z, double radius) {
        World world = server.getWorld(worldName);
        if (world == null) return List.of();
        Location center = new Location(world, x, y, z);
        double radiusSquared = radius * radius;
        List<Actor> players = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= radiusSquared) {
                players.add(actor(player));
            }
        }
        return players;
    }

    // ============================================================
    // Formatting and click actions
    // ============================================================

    @Override
    public String format(Message message, String content) {
        try {
            Language source = message.langSource() != null
                && message.langSource() != Language.AUTO
                ? message.langSource()
                : host.config().defaultLanguage();
            Language target = message.resolvedSourceLanguage() != null
                ? message.resolvedSourceLanguage()
                : source;

            TemplateContext context = TemplateContext.builder(message.sender(), source, target)
                .content(content)
                .translate(message.shouldTranslate())
                .build();
            Component formatted = host.formatter().format(message, context);
            return PlainTextComponentSerializer.plainText().serialize(formatted);
        } catch (Exception e) {
            logger.warn("Failed to format in-world message: " + e.getMessage());
            return content;
        }
    }

    @Override
    public void openUrl(Actor actor, String url) {
        if (actor.handle() instanceof Player player) {
            player.sendMessage("<click:open_url:'" + url + "'><hover:show_text:'Click to open'>"
                + url + "</hover></click>");
        }
    }

    @Override
    public void runCommand(Actor actor, String command) {
        if (actor.handle() instanceof Player player) {
            player.performCommand(command);
        }
    }

    @Override
    public void suggestCommand(Actor actor, String command) {
        if (actor.handle() instanceof Player player) {
            player.sendMessage("<click:suggest_command:'" + command
                + "'><hover:show_text:'Click to suggest'>" + command + "</hover></click>");
        }
    }

    @Override
    public void changePage(Actor actor, String page) {
        logger.debug("change_page is only valid inside a book hover: " + page);
    }

    @Override
    public void copyToClipboard(Actor actor, String text) {
        if (actor.handle() instanceof Player player) {
            player.sendMessage("<click:copy_to_clipboard:'" + text
                + "'><hover:show_text:'Click to copy'>" + text + "</hover></click>");
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Actor actor(Player player) {
        return new Actor(player.getUniqueId(), player.getName(),
            Actor.ActorKind.PLAYER, languageOf(player), player);
    }

    private Language languageOf(Player player) {
        UUID uuid = player.getUniqueId();
        return languages.languageOf(uuid)
            .flatMap(Language::of)
            .orElse(Language.AUTO);
    }
}
