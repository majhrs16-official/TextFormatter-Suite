package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.inworld.InWorldHandler;
import me.majhrs16.suite.inworld.InWorldPlatform;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fabric adapter for the neutral in-world domain.
 *
 * <p>Extracts Fabric events, supplies live actors and delegates message
 * construction to {@link InWorldHandler}. Formatting and delivery stay in the
 * host pipeline through neutral ports.</p>
 */
public final class FabricInWorldHandler implements InWorldPlatform {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicReference<FabricInWorldHandler> ACTIVE =
        new AtomicReference<>();

    private final InWorldHandler handler;
    private final MessageRenderer renderer;
    private final MessageDispatch dispatch;
    private final MinecraftServer server;
    private final PluginLogger logger;
    private final UserLanguageStore languages;
    private final Language defaultLanguage;

    public FabricInWorldHandler(InWorldHandler handler,
                                MessageRenderer renderer,
                                MessageDispatch dispatch,
                                MinecraftServer server,
                                PluginLogger logger,
                                UserLanguageStore languages,
                                Language defaultLanguage) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.server = Objects.requireNonNull(server, "server");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.languages = Objects.requireNonNull(languages, "languages");
        this.defaultLanguage = defaultLanguage;
    }

    /**
     * Registers the Fabric event callbacks for in-world interactions.
     *
     * <p>Fabric does not expose a server-side sign-edit callback; sign text
     * changes arrive through {@link #processSignChange(ServerPlayerEntity,
     * BlockPos, String[])}, which callers can invoke from their own sign
     * editor integration.</p>
     */
    public void registerListeners() {
        ACTIVE.set(this);
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            FabricInWorldHandler current = ACTIVE.get();
            if (current == null || world.isClient()
                || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            current.handleUseBlock(serverPlayer, world, hand, hitResult);
            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            FabricInWorldHandler current = ACTIVE.get();
            if (current != null && !world.isClient()) {
                current.logger.debug("Suite in-world block broken at " + pos);
            }
            return true;
        });

        logger.info("FabricInWorldHandler registered");
    }

    /** Detaches this instance from the Fabric callbacks registered by the mod. */
    public void unregister() {
        ACTIVE.compareAndSet(this, null);
    }

    private void handleUseBlock(ServerPlayerEntity player, World world, Hand hand,
                                BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        String blockKey = world.getBlockState(pos).getBlock().getTranslationKey()
            .toLowerCase(Locale.ROOT);
        if (blockKey.contains("chest")
            || blockKey.contains("barrel")
            || blockKey.contains("shulker")) {
            handleChestOpen(player, pos);
        }

        ItemStack stack = player.getStackInHand(hand);
        if (stack.isOf(Items.WRITTEN_BOOK) || stack.isOf(Items.WRITABLE_BOOK)) {
            handleBookRead(player, stack);
        }
    }

    /**
     * Processes a sign text change through the neutral domain.
     *
     * <p>Called by the platform sign-editor integration when the suite owns the
     * sign. Each non-empty line becomes a message; the formatted line is written
     * back to {@code lines}.</p>
     */
    public void processSignChange(ServerPlayerEntity player, BlockPos pos, String[] lines) {
        String world = worldName(player.getServerWorld());
        String channelName = handler.signChannel(world, pos.getX(), pos.getY(), pos.getZ())
            .orElse(null);
        if (channelName == null) {
            return;
        }

        Actor actor = actor(player);
        for (int i = 0; i < lines.length; i++) {
            String original = lines[i];
            Optional<Message> message = handler.signMessage(actor, channelName, original);
            if (message.isEmpty()) {
                continue;
            }
            dispatch.dispatch(message.get());
            lines[i] = format(message.get(), original);
        }

        handler.cacheSign(world, pos.getX(), pos.getY(), pos.getZ(), channelName, lines);
        logger.debug("Suite sign updated at " + pos + " for channel " + channelName);
    }

    // ============================================================
    // Chest / Container / Book Interactions
    // ============================================================

    public void handleChestOpen(ServerPlayerEntity player, BlockPos pos) {
        String world = worldName(player.getServerWorld());
        handler.containerMessage(actor(player), "container.chest", world,
                pos.getX(), pos.getY(), pos.getZ())
            .ifPresent(dispatch::dispatch);
    }

    public void handleContainerOpen(ServerPlayerEntity player, BlockPos pos) {
        handleChestOpen(player, pos);
    }

    public void handleBookRead(ServerPlayerEntity player, ItemStack book) {
        WrittenBookContentComponent content =
            book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        List<String> pages = content == null
            ? List.of()
            : content.getPages(false).stream().map(Text::getString).toList();
        handler.bookMessage(actor(player), "book.read",
                book.getName().getString(), pages)
            .ifPresent(dispatch::dispatch);
    }

    // ============================================================
    // WORLD / RADIUS Channels
    // ============================================================

    @Override
    public List<Actor> playersInWorld(String worldName) {
        List<Actor> players = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            if (!worldName(world).equals(worldName)) {
                continue;
            }
            for (ServerPlayerEntity player : world.getPlayers()) {
                players.add(actor(player));
            }
        }
        return players;
    }

    @Override
    public List<Actor> playersInRadius(String worldName, double x, double y,
                                       double z, double radius) {
        BlockPos center = BlockPos.ofFloored(x, y, z);
        List<Actor> players = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            if (!worldName(world).equals(worldName)) {
                continue;
            }
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player.getBlockPos().isWithinDistance(center, radius)) {
                    players.add(actor(player));
                }
            }
        }
        return players;
    }

    public List<Actor> getPlayersInWorld(String worldName) {
        return playersInWorld(worldName);
    }

    public List<Actor> getPlayersInRadius(BlockPos center, double radius) {
        List<Actor> players = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (player.getBlockPos().isWithinDistance(center, radius)) {
                    players.add(actor(player));
                }
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
            return renderer.render(message, content, message.sender(), defaultLanguage);
        } catch (Exception e) {
            logger.warn("Failed to format in-world message: " + e.getMessage());
            return content;
        }
    }

    @Override
    public void openUrl(Actor actor, String url) {
        sendClick(actor, url, ClickEvent.Action.OPEN_URL, url);
    }

    @Override
    public void runCommand(Actor actor, String command) {
        if (actor.handle() instanceof ServerPlayerEntity player) {
            server.getCommandManager().executeWithPrefix(player.getCommandSource(), command);
        }
    }

    @Override
    public void suggestCommand(Actor actor, String command) {
        sendClick(actor, command, ClickEvent.Action.SUGGEST_COMMAND, command);
    }

    @Override
    public void changePage(Actor actor, String page) {
        sendClick(actor, "Page " + page, ClickEvent.Action.CHANGE_PAGE, page);
    }

    @Override
    public void copyToClipboard(Actor actor, String text) {
        sendClick(actor, text, ClickEvent.Action.COPY_TO_CLIPBOARD, text);
    }

    public void handleClick(Actor actor, String action, String value) {
        handler.handleClick(this, actor, action, value);
    }

    public String getHoverText(Actor actor, String key) {
        return handler.getHoverText(actor, key);
    }

    // ============================================================
    // Glossary / Cache
    // ============================================================

    public void addGlossaryEntry(String key, String definition) {
        handler.addGlossaryEntry(key, definition);
    }

    public String getGlossaryEntry(String key) {
        return handler.getGlossaryEntry(key);
    }

    public void clearGlossary() {
        handler.clearGlossary();
    }

    public void updateRadiusCache(UUID playerId, BlockPos location, double radius) {
        handler.updateRadiusCache(playerId, "", location.getX(), location.getY(),
            location.getZ(), radius);
    }

    public Optional<InWorldHandler.RadiusData> getRadiusData(UUID playerId) {
        return handler.getRadiusData(playerId);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void sendClick(Actor actor, String label, ClickEvent.Action action,
                           String value) {
        if (actor.handle() instanceof ServerPlayerEntity player) {
            Text text = Text.literal(label)
                .styled(style -> style.withClickEvent(new ClickEvent(action, value)));
            player.sendMessage(text);
        }
    }

    private Actor actor(ServerPlayerEntity player) {
        return new Actor(player.getUuid(), player.getName().getString(),
            Actor.ActorKind.PLAYER, languageOf(player), player);
    }

    private Language languageOf(ServerPlayerEntity player) {
        return languages.languageOf(player.getUuid())
            .flatMap(Language::of)
            .orElse(Language.AUTO);
    }

    private static String worldName(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }
}
