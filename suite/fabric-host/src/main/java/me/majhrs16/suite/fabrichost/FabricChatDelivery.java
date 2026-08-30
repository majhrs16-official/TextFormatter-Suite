package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.host.port.ChatDelivery;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric implementation of {@link ChatDelivery} using native Fabric APIs.
 */
public final class FabricChatDelivery implements ChatDelivery {

    private final MinecraftServer server;
    private static final Logger LOGGER = LoggerFactory.getLogger("TextFormatterSuite");
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    public FabricChatDelivery(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void deliver(Actor recipient, Component rendered, Message original) {
        if (recipient == null) return;
        ServerPlayerEntity player = recipient.handle();
        if (player != null) {
            String json = GSON.serialize(rendered);
            Text text = net.minecraft.text.TextCodecs.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(json)).getOrThrow();
            server.execute(() -> player.sendMessage(text));
        }
    }

    @Override
    public void deliverConsole(Component rendered) {
        server.execute(() -> LOGGER.info(rendered.toString()));
    }

    @Override
    public void playSound(Actor recipient, SoundSpec sound) {
        ServerPlayerEntity player = recipient == null ? null : recipient.handle();
        if (player == null) return;

        SoundEvent nativeSound = resolve(sound.name());
        if (nativeSound == null) return;

        server.execute(() -> player.playSound(nativeSound, sound.volume(), sound.pitch()));
    }

    @Override
    public boolean hasSound(String soundName) {
        return resolve(soundName) != null;
    }

    static SoundEvent resolve(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.toLowerCase(java.util.Locale.ROOT)
            .replace('.', '_')
            .replace('-', '_');
        String base = stripAudioExtension(normalized);
        try {
            Identifier id = Identifier.of(base);
            if (Registries.SOUND_EVENT.containsId(id)) {
                return Registries.SOUND_EVENT.get(id);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripAudioExtension(String normalized) {
        for (String ext : new String[] {"_mp3", "_ogg", "_wav"}) {
            if (normalized.endsWith(ext)) {
                return normalized.substring(0, normalized.length() - ext.length());
            }
        }
        return normalized;
    }
}
