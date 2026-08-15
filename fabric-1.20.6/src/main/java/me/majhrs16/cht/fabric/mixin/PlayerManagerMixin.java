package me.majhrs16.cht.fabric.mixin;

import me.majhrs16.cht.core.ChatTranslatorApp;
import me.majhrs16.cht.core.message.ChatMessage;
import me.majhrs16.cht.core.message.ChatMessageType;
import me.majhrs16.cht.core.player.Subject;
import me.majhrs16.cht.fabric.ChatTranslatorMod;
import me.majhrs16.cht.fabric.FabricPlayerRegistry;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Language;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates and suppresses the vanilla "general" broadcasts that Spigot
 * handles through its {@code PlayerJoinEvent}/{@code PlayerDeathEvent}/etc.:
 *
 * <ul>
 *   <li>{@code death.*} keys -- death messages;</li>
 *   <li>{@code multiplayer.player.joined} / {@code multiplayer.player.left} --
 *       join and leave messages;</li>
 *   <li>{@code chat.type.advancement*} -- advancement announcements.</li>
 * </ul>
 *
 * <p>These are all sent through {@link PlayerManager#broadcast(Text, boolean)},
 * so a single injection point covers them. Recognized broadcasts are turned
 * into neutral {@link ChatMessage}s for the routing engine and cancelled;
 * unknown messages fall through untouched.</p>
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void cht_translateBroadcast(Text message, boolean overlay, CallbackInfo ci) {
        ChatTranslatorApp app = ChatTranslatorMod.app();
        if (app == null) {
            return;
        }
        TextContent textContent = message.getContent();
        if (!(textContent instanceof TranslatableTextContent)) {
            return;
        }
        TranslatableTextContent translatable = (TranslatableTextContent) textContent;
        ChatMessageType type = classify(translatable.getKey());
        if (type == null) {
            return; // not a message the engine owns: keep the vanilla broadcast
        }
        String content;
        if (type == ChatMessageType.JOIN || type == ChatMessageType.LEAVE) {
            content = "";
        } else {
            String localized = localize(translatable);
            if (localized == null) {
                return; // cannot render, let the vanilla broadcast run
            }
            content = localized;
        }
        PlayerManager self = (PlayerManager) (Object) this;
        Subject sender = resolveSender(self, translatable.getArgs());
        app.sendMessage(ChatMessage.builder(type, sender).content(content).build());
        ci.cancel();
    }

    private static ChatMessageType classify(String key) {
        if (key.startsWith("death.")) {
            return ChatMessageType.DEATH;
        }
        if (key.equals("multiplayer.player.joined")) {
            return ChatMessageType.JOIN;
        }
        if (key.equals("multiplayer.player.left")) {
            return ChatMessageType.LEAVE;
        }
        if (key.startsWith("chat.type.advancement")) {
            return ChatMessageType.ADVANCEMENT;
        }
        return null;
    }

    private static Subject resolveSender(PlayerManager manager, Object[] args) {
        String name = displayName(args);
        if (name != null) {
            ServerPlayerEntity player = manager.getPlayer(name);
            if (player != null) {
                return FabricPlayerRegistry.toSubject(player);
            }
        }
        return Subject.unknown(name == null ? "?" : name);
    }

    private static String displayName(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object first = args[0];
        if (first instanceof Text) {
            return ((Text) first).getString();
        }
        return first == null ? null : String.valueOf(first);
    }

    /**
     * Renders a translatable message in the server's default language, so the
     * routing engine can translate it again per recipient. In 1.20.x
     * {@code Text.getString()} only yields a debug representation for
     * translatable components, so the template is read from the server language
     * table and the args are substituted manually.
     *
     * @return the formatted plain text, or {@code null} when the key is unknown.
     */
    private static String localize(TranslatableTextContent translatable) {
        String template = Language.getInstance().get(translatable.getKey(), translatable.getKey());
        if (template == null) {
            return null;
        }
        Object[] args = translatable.getArgs();
        StringBuilder result = new StringBuilder(template.length() + 16);
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c != '%') {
                result.append(c);
                continue;
            }
            if (i + 1 >= template.length()) {
                result.append('%');
                break;
            }
            char next = template.charAt(i + 1);
            if (next == '%') {
                result.append('%');
                i++;
            } else if (next == 's' || next == 'S') {
                result.append(arg(args, 0));
                i++;
            } else if (Character.isDigit(next)) {
                int end = i + 1;
                while (end < template.length() && Character.isDigit(template.charAt(end))) {
                    end++;
                }
                int index = Integer.parseInt(template.substring(i + 1, end)) - 1;
                i = end;
                if (i + 1 < template.length() && template.charAt(i) == '$') {
                    i++;
                }
                if (i + 1 < template.length() && (template.charAt(i) == 's' || template.charAt(i) == 'S')) {
                    i++;
                }
                result.append(arg(args, index));
            } else {
                result.append('%');
            }
        }
        return result.toString();
    }

    private static String arg(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            return "";
        }
        Object value = args[index];
        if (value instanceof Text) {
            return ((Text) value).getString();
        }
        return value == null ? "" : String.valueOf(value);
    }
}