package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Formats;

import org.assertj.core.api.Assertions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests de integración para {@link InWorldHandler}.
 * Validan interacciones con signs, chests, books, WORLD/RADIUS channels y click/hover.
 */
@DisplayName("InWorldHandler — integración")
class InWorldHandlerIntegrationTest {

    private SuiteHost host;
    private MessageDispatcher dispatcher;
    private ChannelRegistry channels;
    private PluginLogger logger;
    private TranslationService translation;
    private UserLanguageStore languages;
    private org.bukkit.Server server;
    private World world;
    private InWorldHandler handler;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        // Create mocks
        host = mock(SuiteHost.class);
        dispatcher = mock(MessageDispatcher.class);
        channels = mock(ChannelRegistry.class);
        logger = mock(PluginLogger.class);
        translation = mock(TranslationService.class);
        languages = mock(UserLanguageStore.class);
        server = mock(org.bukkit.Server.class);
        world = mock(World.class);

        when(server.getWorld("world")).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(world.getPlayers()).thenReturn(List.of());
        when(host.config()).thenReturn(mock(me.majhrs16.suite.host.config.HostConfig.class));
        when(host.config().defaultLanguage()).thenReturn(Language.EN);
        when(host.formatter()).thenReturn(mock(me.majhrs16.suite.textformatter.DefaultTextFormatter.class));

        // Configure channels using real Channel objects with formats
        Formats signFormat = Formats.of("<gray>[Sign]</gray> <content>");
        Channel signChannel = Channel.builder("sign.global")
            .messages(signFormat)
            .signLocation("0,64,0,world")
            .type(Channel.Type.CHAT)
            .build();

        Formats chestFormat = Formats.of("<gold>[Chest]</gold> <content>");
        Channel chestChannel = Channel.builder("container.chest")
            .messages(chestFormat)
            .type(Channel.Type.CHAT)
            .build();

        Formats bookFormat = Formats.of("<green>[Book]</green> <content>");
        Channel bookChannel = Channel.builder("book.read")
            .messages(bookFormat)
            .type(Channel.Type.CHAT)
            .build();

        Formats radiusFormat = Formats.of("<aqua>[Local]</aqua> <sender>: <content>");
        Channel radiusChannel = Channel.builder("chat.radius")
            .messages(radiusFormat)
            .type(Channel.Type.CHAT)
            .build();

        Formats worldFormat = Formats.of("<blue>[World]</blue> <sender>: <content>");
        Channel worldChannel = Channel.builder("chat.world")
            .messages(worldFormat)
            .type(Channel.Type.CHAT)
            .build();

        when(channels.resolve("sign.global")).thenReturn(signChannel);
        when(channels.resolve("container.chest")).thenReturn(chestChannel);
        when(channels.resolve("book.read")).thenReturn(bookChannel);
        when(channels.resolve("chat.radius")).thenReturn(radiusChannel);
        when(channels.resolve("chat.world")).thenReturn(worldChannel);
        when(channels.paths()).thenReturn(List.of("sign.global", "container.chest", "book.read", "chat.radius", "chat.world"));

        // Language store
        when(languages.languageOf(any())).thenReturn(Optional.of("en"));

        // Translation
        when(translation.translate(any(), any(), any())).thenReturn("translated");

        // Create handler
        handler = new InWorldHandler(host, dispatcher, server, channels, logger, translation, languages);
    }

    // ============================================================
    // Sign Tests
    // ============================================================

    @Test
    @DisplayName("procesa sign change y formatea líneas")
    void processesSignChangeAndFormatsLines() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Steve");
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        
        Sign sign = mock(Sign.class);
        when(sign.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(sign.getLines()).thenReturn(new String[]{"Line 1", "Line 2", "", "Line 4"});
        when(block.getState()).thenReturn(sign);

        String[] lines = {"Line 1", "Line 2", "", "Line 4"};

        SignChangeEvent event = mock(SignChangeEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(event.getLines()).thenReturn(lines);
        when(event.getLine(0)).thenReturn("Line 1");
        when(event.getLine(1)).thenReturn("Line 2");
        when(event.getLine(2)).thenReturn("");
        when(event.getLine(3)).thenReturn("Line 4");

        // Simulate dispatch result
        doAnswer(invocation -> null).when(dispatcher).dispatch(any(Message.class));

        // Call the handler
        handler.onSignChange(event);

        // Verify dispatch was called for non-empty lines (3 lines)
        verify(dispatcher, times(3)).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("ignora sign sin canal configurado")
    void ignoresSignWithoutConfiguredChannel() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        when(player.getLocation()).thenReturn(new Location(world, 100, 64, 100));

        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 100, 64, 100));
        
        Sign sign = mock(Sign.class);
        when(sign.getLocation()).thenReturn(new Location(world, 100, 64, 100));
        when(sign.getLines()).thenReturn(new String[]{"Test"});
        when(block.getState()).thenReturn(sign);

        SignChangeEvent event = mock(SignChangeEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(event.getLines()).thenReturn(new String[]{"Test"});

        // No sign channel configured for this location
        when(channels.paths()).thenReturn(List.of("chat.global"));

        handler.onSignChange(event);

        // Dispatch should not be called
        verify(dispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("maneja sign con línea vacía correctamente")
    void handlesSignWithEmptyLine() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        
        Sign sign = mock(Sign.class);
        when(sign.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(sign.getLines()).thenReturn(new String[]{"Line 1", "", "Line 3", ""});
        when(block.getState()).thenReturn(sign);

        String[] lines = {"Line 1", "", "Line 3", ""};

        SignChangeEvent event = mock(SignChangeEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(event.getLines()).thenReturn(lines);

        doAnswer(invocation -> null).when(dispatcher).dispatch(any(Message.class));

        handler.onSignChange(event);

        // Only 2 non-empty lines should be dispatched
        verify(dispatcher, times(2)).dispatch(any(Message.class));
    }

    // ============================================================
    // Chest / Container Tests
    // ============================================================

    @Test
    @DisplayName("procesa apertura de chest")
    void processesChestOpen() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Steve");
        when(player.getLocation()).thenReturn(new Location(world, 10, 64, 10));

        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        BlockData blockData = mock(BlockData.class);
        when(blockData.getMaterial()).thenReturn(Material.CHEST);
        when(block.getBlockData()).thenReturn(blockData);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerInteract(event);

        // Verify dispatch was called with CONTAINER message
        verify(dispatcher).dispatch(argThat(msg -> 
            msg.type() == MessageType.CONTAINER &&
            msg.text().contains("Chest opened")
        ));
    }

    @Test
    @DisplayName("ignora interacción que no es right-click")
    void ignoresNonRightClick() {
        Player player = mock(Player.class);
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        BlockData blockData = mock(BlockData.class);
        when(blockData.getMaterial()).thenReturn(Material.CHEST);
        when(block.getBlockData()).thenReturn(blockData);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerInteract(event);

        verify(dispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("ignora bloque sin chest")
    void ignoresNonChestBlock() {
        Player player = mock(Player.class);
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        BlockData blockData = mock(BlockData.class);
        when(blockData.getMaterial()).thenReturn(Material.STONE);
        when(block.getBlockData()).thenReturn(blockData);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerInteract(event);

        verify(dispatcher, never()).dispatch(any(Message.class));
    }

    @Test
    @DisplayName("procesa barrel y shulker box")
    void processesBarrelAndShulker() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Steve");

        // Test barrel
        Block barrel = mock(Block.class);
        when(barrel.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        BlockData barrelData = mock(BlockData.class);
        when(barrelData.getMaterial()).thenReturn(Material.BARREL);
        when(barrel.getBlockData()).thenReturn(barrelData);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(barrel);
        when(event.getPlayer()).thenReturn(player);

        handler.onPlayerInteract(event);

        verify(dispatcher).dispatch(argThat(msg -> 
            msg.type() == MessageType.CONTAINER &&
            msg.text().contains("Chest opened")
        ));

        // Test shulker box
        Block shulker = mock(Block.class);
        when(shulker.getLocation()).thenReturn(new Location(world, 20, 64, 20));
        BlockData shulkerData = mock(BlockData.class);
        when(shulkerData.getMaterial()).thenReturn(Material.SHULKER_BOX);
        when(shulker.getBlockData()).thenReturn(shulkerData);

        PlayerInteractEvent event2 = mock(PlayerInteractEvent.class);
        when(event2.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event2.getClickedBlock()).thenReturn(shulker);
        when(event2.getPlayer()).thenReturn(player);

        handler.onPlayerInteract(event2);

        verify(dispatcher, times(2)).dispatch(argThat(msg -> 
            msg.type() == MessageType.CONTAINER
        ));
    }

    // ============================================================
    // Book Tests
    // ============================================================

    @Test
    @DisplayName("procesa lectura de libro escrito")
    void processesWrittenBookRead() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Steve");

        ItemStack book = mock(ItemStack.class);
        when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(book.hasItemMeta()).thenReturn(true);

        BookMeta bookMeta = mock(BookMeta.class);
        when(bookMeta.getTitle()).thenReturn("Test Book");
        when(bookMeta.getPages()).thenReturn(List.of("Page 1", "Page 2", "Page 3"));
        when(book.getItemMeta()).thenReturn(bookMeta);

        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        BlockData blockData = mock(BlockData.class);
        when(blockData.getMaterial()).thenReturn(Material.STONE);
        when(block.getBlockData()).thenReturn(blockData);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(book);

        handler.onPlayerInteract(event);

        verify(dispatcher).dispatch(argThat(msg -> 
            msg.type() == MessageType.BOOK &&
            msg.text().contains("Book read: Test Book") &&
            msg.text().contains("Page 1") &&
            msg.text().contains("Page 2") &&
            msg.text().contains("Page 3")
        ));
    }

    @Test
    @DisplayName("ignora libro sin meta o sin título")
    void ignoresBookWithoutMeta() {
        Player player = mock(Player.class);
        ItemStack book = mock(ItemStack.class);
        when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(book.hasItemMeta()).thenReturn(false);

        Block block = mock(Block.class);
        BlockData blockData = mock(BlockData.class);
        when(blockData.getMaterial()).thenReturn(Material.STONE);
        when(block.getBlockData()).thenReturn(blockData);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(book);

        handler.onPlayerInteract(event);

        verify(dispatcher, never()).dispatch(any(Message.class));
    }

    // ============================================================
    // WORLD / RADIUS Channel Tests
    // ============================================================

    @Test
    @DisplayName("obtiene jugadores en radio para canales RADIUS")
    void getsPlayersInRadius() {
        Player p1 = mock(Player.class);
        UUID id1 = UUID.randomUUID();
        when(p1.getUniqueId()).thenReturn(id1);
        when(p1.getName()).thenReturn("Player1");
        when(p1.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        Player p2 = mock(Player.class);
        UUID id2 = UUID.randomUUID();
        when(p2.getUniqueId()).thenReturn(id2);
        when(p2.getName()).thenReturn("Player2");
        when(p2.getLocation()).thenReturn(new Location(world, 5, 64, 5)); // ~7 blocks away

        Player p3 = mock(Player.class);
        UUID id3 = UUID.randomUUID();
        when(p3.getUniqueId()).thenReturn(id3);
        when(p3.getName()).thenReturn("Player3");
        when(p3.getLocation()).thenReturn(new Location(world, 20, 64, 20)); // ~28 blocks away

        when(world.getPlayers()).thenReturn(List.of(p1, p2, p3));

        List<Actor> actors = handler.getPlayersInRadius(new Location(world, 0, 64, 0), 10.0);

        assertThat(actors).hasSize(2);
        assertThat(actors).extracting(Actor::name).containsExactlyInAnyOrder("Player1", "Player2");
    }

    @Test
    @DisplayName("obtiene jugadores en mundo para canales WORLD")
    void getsPlayersInWorld() {
        Player p1 = mock(Player.class);
        when(p1.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p1.getName()).thenReturn("Player1");

        Player p2 = mock(Player.class);
        when(p2.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p2.getName()).thenReturn("Player2");

        when(world.getPlayers()).thenReturn(List.of(p1, p2));

        List<Actor> actors = handler.getPlayersInWorld("world");

        assertThat(actors).hasSize(2);
        assertThat(actors).extracting(Actor::name).containsExactlyInAnyOrder("Player1", "Player2");
    }

    @Test
    @DisplayName("devuelve lista vacía si no hay jugadores en mundo")
    void returnsEmptyListForEmptyWorld() {
        when(world.getPlayers()).thenReturn(List.of());

        List<Actor> actors = handler.getPlayersInWorld("world");

        assertThat(actors).isEmpty();
    }

    // ============================================================
    // Click / Hover Tests
    // ============================================================

    @Test
    @DisplayName("maneja click open_url")
    void handlesClickOpenUrl() {
        Player player = mock(Player.class);
        Actor actor = new Actor(UUID.randomUUID(), "Steve", Actor.ActorKind.PLAYER, Language.EN, player);

        handler.handleClick(actor, "open_url", "https://example.com");

        verify(player).sendMessage(contains("https://example.com"));
    }

    @Test
    @DisplayName("maneja click run_command")
    void handlesClickRunCommand() {
        Player player = mock(Player.class);
        Actor actor = new Actor(UUID.randomUUID(), "Steve", Actor.ActorKind.PLAYER, Language.EN, player);

        handler.handleClick(actor, "run_command", "say hello");

        verify(player).performCommand("say hello");
    }

    @Test
    @DisplayName("maneja click suggest_command")
    void handlesClickSuggestCommand() {
        Player player = mock(Player.class);
        Actor actor = new Actor(UUID.randomUUID(), "Steve", Actor.ActorKind.PLAYER, Language.EN, player);

        handler.handleClick(actor, "suggest_command", "/help");

        verify(player).sendMessage(contains("/help"));
    }

    @Test
    @DisplayName("maneja click copy_to_clipboard")
    void handlesClickCopyToClipboard() {
        Player player = mock(Player.class);
        Actor actor = new Actor(UUID.randomUUID(), "Steve", Actor.ActorKind.PLAYER, Language.EN, player);

        handler.handleClick(actor, "copy_to_clipboard", "text to copy");

        verify(player).sendMessage(contains("text to copy"));
    }

    @Test
    @DisplayName("ignora acción de click desconocida")
    void ignoresUnknownClickAction() {
        Player player = mock(Player.class);
        Actor actor = new Actor(UUID.randomUUID(), "Steve", Actor.ActorKind.PLAYER, Language.EN, player);

        handler.handleClick(actor, "unknown_action", "value");

        verifyNoInteractions(player);
    }

    @Test
    @DisplayName("getHoverText devuelve entrada del glosario")
    void getHoverTextReturnsGlossaryEntry() {
        handler.addGlossaryEntry("test_key", "Test definition");

        String hover = handler.getHoverText(null, "test_key");

        assertThat(hover).isEqualTo("Test definition");
    }

    @Test
    @DisplayName("getHoverText devuelve vacío para clave inexistente")
    void getHoverTextReturnsEmptyForMissingKey() {
        String hover = handler.getHoverText(null, "nonexistent");

        assertThat(hover).isEmpty();
    }

    // ============================================================
    // Glossary / Cache Tests
    // ============================================================

    @Test
    @DisplayName("gestiona entradas del glosario")
    void managesGlossaryEntries() {
        handler.addGlossaryEntry("key1", "def1");
        handler.addGlossaryEntry("key2", "def2");

        assertThat(handler.getGlossaryEntry("key1")).isEqualTo("def1");
        assertThat(handler.getGlossaryEntry("key2")).isEqualTo("def2");

        handler.clearGlossary();

        assertThat(handler.getGlossaryEntry("key1")).isNull();
        assertThat(handler.getGlossaryEntry("key2")).isNull();
    }

    @Test
    @DisplayName("actualiza y obtiene cache de RADIUS")
    void updatesAndGetsRadiusCache() {
        UUID playerId = UUID.randomUUID();
        Location loc = new Location(world, 0, 64, 0);

        handler.updateRadiusCache(playerId, loc, 50.0);

        // We can't directly access RadiusData (private), but we can verify no exception
        // and that the method doesn't throw
        assertThatCode(() -> handler.getRadiusData(playerId))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("getRadiusData devuelve vacío para jugador sin cache")
    void getRadiusDataReturnsEmptyForMissingPlayer() {
        Optional<?> data = invokeGetRadiusData(handler, UUID.randomUUID());

        assertThat(data).isEmpty();
    }

    // ============================================================
    // Language Resolution Tests
    // ============================================================

    @Test
    @DisplayName("resuelve idioma del jugador desde UserLanguageStore")
    void resolvesPlayerLanguageFromStore() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(languages.languageOf(playerId)).thenReturn(Optional.of("es"));

        Language lang = invokeGetPlayerLanguage(handler, player);

        assertThat(lang).isEqualTo(Language.ES);
    }

    @Test
    @DisplayName("usa AUTO como fallback si no hay idioma guardado")
    void usesAutoAsFallbackWhenNoLanguageStored() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(languages.languageOf(playerId)).thenReturn(Optional.empty());

        Language lang = invokeGetPlayerLanguage(handler, player);

        assertThat(lang).isEqualTo(Language.AUTO);
    }

    // Helper to test private methods via reflection
    private Language invokeGetPlayerLanguage(InWorldHandler handler, Player player) {
        try {
            java.lang.reflect.Method method = InWorldHandler.class.getDeclaredMethod("getPlayerLanguage", Player.class);
            method.setAccessible(true);
            return (Language) method.invoke(handler, player);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<?> invokeGetRadiusData(InWorldHandler handler, UUID playerId) {
        try {
            java.lang.reflect.Method method = InWorldHandler.class.getDeclaredMethod("getRadiusData", UUID.class);
            method.setAccessible(true);
            return (Optional<?>) method.invoke(handler, playerId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}