package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.ChatTranslatorApp;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.ChatMessage;
import me.majhrs16.cht.core.message.ChatMessageType;
import me.majhrs16.cht.core.player.Subject;

import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Translates native Bukkit events into neutral {@link ChatMessage}s and hands
 * them to the routing engine.
 */
final class ChatListener implements Listener {

    private final ChatTranslatorApp app;

    ChatListener(ChatTranslatorApp app) {
        this.app = app;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);

        ChatMessage message = ChatMessage
            .builder(ChatMessageType.CHAT, SpigotPlayerRegistry.toSubject(player))
            .content(event.getMessage())
            .sourceLanguage(languageOf(NmsLocaleBridge.localeOf(player)))
            .build();
        app.sendMessage(message);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        Player player = event.getPlayer();
        ChatMessage message = ChatMessage
            .builder(ChatMessageType.JOIN, SpigotPlayerRegistry.toSubject(player))
            .content("")
            .build();
        app.sendMessage(message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        Player player = event.getPlayer();
        ChatMessage message = ChatMessage
            .builder(ChatMessageType.LEAVE, SpigotPlayerRegistry.toSubject(player))
            .content("")
            .build();
        app.sendMessage(message);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        String deathMessage = event.getDeathMessage();
        if (deathMessage == null || deathMessage.isEmpty()) {
            return;
        }
        event.setDeathMessage(null);
        Player player = event.getEntity();
        ChatMessage message = ChatMessage
            .builder(ChatMessageType.DEATH, SpigotPlayerRegistry.toSubject(player))
            .content(deathMessage)
            .build();
        app.sendMessage(message);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        ChatMessage message = ChatMessage
            .builder(ChatMessageType.ADVANCEMENT, SpigotPlayerRegistry.toSubject(player))
            .content(event.getAdvancement().getKey().getKey())
            .build();
        app.sendMessage(message);
    }

    @EventHandler
    public void onSignInteract(PlayerInteractEvent event) {
        if (!app.settings().translateSigns()) {
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        if (event.getClickedBlock() == null || !isSign(event.getClickedBlock().getType())) {
            return;
        }
        Sign sign = (Sign) event.getClickedBlock().getState();
        String[] lines = sign.getLines();
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (content.length() > 0) {
                content.append(' ');
            }
            content.append(line);
        }
        if (content.length() == 0) {
            return;
        }
        ChatMessage message = ChatMessage
            .builder(ChatMessageType.SIGN, SpigotPlayerRegistry.toSubject(player))
            .target(SpigotPlayerRegistry.toSubject(player))
            .content(content.toString())
            .sourceLanguage(languageOf(NmsLocaleBridge.localeOf(player)))
            .build();
        app.sendMessage(message);
    }

    private static boolean isSign(Material material) {
        String name = material.name();
        return name.endsWith("_SIGN")
            || name.endsWith("_WALL_SIGN")
            || name.contains("SIGN");
    }

    private static Language languageOf(String locale) {
        if (locale == null || locale.isEmpty()) {
            return null;
        }
        return Language.of(locale).orElse(Language.AUTO);
    }
}