package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.ChatTranslatorApp;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.player.Subject;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * The {@code /cht} command: reload, language management and version lookup.
 */
final class ChtCommand implements CommandExecutor {

    private final ChatTranslatorApp app;
    private final String version;

    ChtCommand(ChatTranslatorApp app, String version) {
        this.app = app;
        this.version = version;
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(banner());
            return true;
        }
        String sub = args[0].toLowerCase();
        if ("reload".equals(sub)) {
            return reload(sender);
        }
        if ("version".equals(sub)) {
            sender.sendMessage(banner());
            return true;
        }
        if ("lang".equals(sub)) {
            return language(sender, args);
        }
        sender.sendMessage("§cUnknown label. Usage: /cht <reload|lang|version>");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("cht.reload")) {
            return deny(sender);
        }
        app.reload();
        sender.sendMessage(banner() + " §aConfiguration reloaded.");
        return true;
    }

    private boolean language(CommandSender sender, String[] args) {
        if (args.length == 1 && !(sender instanceof Player)) {
            sender.sendMessage("§cUsage: /cht lang <player> <code>");
            return true;
        }

        if (args.length == 1 || args.length == 2) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /cht lang <code>");
                return true;
            }
            Player player = (Player) sender;
            String code = args.length == 2 ? args[1] : null;
            return setOrGet(player, player, code);
        }

        if (args.length == 3) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
            return setOrGet(sender, target, args[2]);
        }

        sender.sendMessage("§cUsage: /cht lang [player] <code>");
        return true;
    }

    private boolean setOrGet(CommandSender source, Player target, String code) {
        if (code == null || code.equalsIgnoreCase("get")) {
            Subject subject = SpigotPlayerRegistry.toSubject(target);
            source.sendMessage("§a" + target.getName()
                + " language: §b" + app.languageOf(subject).code());
            return true;
        }
        Optional<Language> language = Language.of(code);
        if (!language.isPresent()) {
            source.sendMessage("§cUnsupported language: " + code);
            return true;
        }
        app.setLanguage(SpigotPlayerRegistry.toSubject(target), language.get());
        target.sendMessage("§aYour language is now "
            + language.get().displayName() + ".");
        if (source != target) {
            source.sendMessage("§aLanguage changed for " + target.getName() + ".");
        }
        return true;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage("§cYou don't have permission to do this.");
        return true;
    }

    private String banner() {
        return "§aChat§9Translator §6v" + version;
    }
}