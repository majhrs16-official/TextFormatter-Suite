package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.message.SoundSpec;
import me.majhrs16.cht.core.platform.ChatDisplay;
import me.majhrs16.cht.core.player.Channel;
import me.majhrs16.cht.core.player.Subject;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Fabric {@link ChatDisplay}. Adventure components are converted to the native
 * JSON {@link Text} format and sent to the player or the server command
 * source.
 */
final class FabricChatDisplay implements ChatDisplay {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final MinecraftServer server;

    FabricChatDisplay(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void send(Subject recipient, Component message, Channel channel) {
        Text nativeText = Text.Serialization.fromJson(
            GSON.serialize(message), server.getRegistryManager());
        if (nativeText == null) {
            return;
        }
        Object handle = recipient.handle();
        if (handle instanceof ServerPlayerEntity) {
            ((ServerPlayerEntity) handle).sendMessage(nativeText);
        } else {
            server.getCommandSource().sendMessage(nativeText);
        }
    }

    @Override
    public void sendToConsole(String message) {
        server.getCommandSource().sendMessage(Text.literal(message));
    }

    @Override
    public void dispatchServerCommand(String command) {
        server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
    }

    @Override
    public void dispatchCommand(Subject actor, String command, Channel channel) {
        Object handle = actor.handle();
        if (handle instanceof ServerPlayerEntity) {
            server.getCommandManager().executeWithPrefix(
                ((ServerPlayerEntity) handle).getCommandSource(), command);
        } else {
            dispatchServerCommand(command);
        }
    }

    @Override
    public void playSound(Subject recipient, SoundSpec sound) {
        Object handle = recipient.handle();
        if (!(handle instanceof ServerPlayerEntity)) {
            return;
        }
        Identifier id = Identifier.tryParse(sound.name());
        if (id == null) {
            id = Identifier.of("minecraft", sound.name());
        }
        SoundEvent event = Registries.SOUND_EVENT.get(id);
        if (event == null) {
            return;
        }
        ((ServerPlayerEntity) handle).playSoundToPlayer(
            event, SoundCategory.MASTER, sound.volume(), sound.pitch());
    }

    @Override
    public boolean hasSound(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null) {
            id = Identifier.of("minecraft", key);
        }
        return Registries.SOUND_EVENT.containsId(id);
    }
}