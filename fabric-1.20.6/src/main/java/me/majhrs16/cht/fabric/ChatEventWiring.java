package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.ChatTranslatorApp;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.ChatMessage;
import me.majhrs16.cht.core.message.ChatMessageType;
import me.majhrs16.cht.fabric.mixin.ServerPlayerEntityAccessor;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents.AllowChatMessage;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Wires the Fabric events the plugin reacts to, converting them into neutral
 * {@link ChatMessage}s for the routing engine.
 *
 * <p>Join, leave, death and advancement messages are NOT wired here: they are
 * produced by the vanilla {@code PlayerManager} broadcast, which the
 * {@code PlayerManagerMixin} translates and suppresses.</p>
 */
final class ChatEventWiring {

    private ChatEventWiring() {
    }

    static void register(ChatTranslatorApp app) {
        chat(app);
        signs(app);
    }

    private static void chat(ChatTranslatorApp app) {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(new AllowChatMessage() {
            @Override
            public boolean allowChatMessage(
                    SignedMessage message,
                    ServerPlayerEntity sender,
                    MessageType.Parameters params) {
                String content = message.getContent().getString();
                if (content.isEmpty()) {
                    return true;
                }
                ChatMessage neutral = ChatMessage
                    .builder(ChatMessageType.CHAT, FabricPlayerRegistry.toSubject(sender))
                    .content(content)
                    .sourceLanguage(languageOf(sender))
                    .build();
                app.sendMessage(neutral);
                return false; // the engine already rendered the message
            }
        });
    }

    /**
     * Sneak + left click on a sign translates its front lines, mirroring the
     * Spigot behaviour. The attack is not cancelled.
     */
    private static void signs(ChatTranslatorApp app) {
        AttackBlockCallback.EVENT.register(
            (PlayerEntity player, World world, Hand hand, BlockPos pos, Direction direction) -> {
                if (!app.settings().translateSigns()) {
                    return ActionResult.PASS;
                }
                if (!player.isSneaking() || !(player instanceof ServerPlayerEntity)) {
                    return ActionResult.PASS;
                }
                if (!(world.getBlockEntity(pos) instanceof SignBlockEntity)) {
                    return ActionResult.PASS;
                }
                SignText front = ((SignBlockEntity) world.getBlockEntity(pos)).getFrontText();
                StringBuilder content = new StringBuilder();
                for (int line = 0; line < 4; line++) {
                    String text = front.getMessage(line, false).getString().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    if (content.length() > 0) {
                        content.append(' ');
                    }
                    content.append(text);
                }
                if (content.length() == 0) {
                    return ActionResult.PASS;
                }
                ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
                ChatMessage message = ChatMessage
                    .builder(ChatMessageType.SIGN, FabricPlayerRegistry.toSubject(serverPlayer))
                    .target(FabricPlayerRegistry.toSubject(serverPlayer))
                    .content(content.toString())
                    .sourceLanguage(languageOf(serverPlayer))
                    .build();
                app.sendMessage(message);
                return ActionResult.PASS;
            });
    }

    private static Language languageOf(ServerPlayerEntity player) {
        return Language.of(((ServerPlayerEntityAccessor) player).getLanguage())
            .orElse(Language.AUTO);
    }
}