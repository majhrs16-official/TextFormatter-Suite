package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.platform.PermissionChecker;
import me.majhrs16.cht.core.player.Subject;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permissible;

/**
 * Spigot {@link PermissionChecker}. The console is always allowed.
 */
final class SpigotPermissionChecker implements PermissionChecker {

    @Override
    public boolean has(Subject subject, String node) {
        Object handle = subject.handle();
        if (handle instanceof Permissible) {
            return ((Permissible) handle).hasPermission(node);
        }
        return subject.kind() == Subject.SubjectKind.CONSOLE;
    }
}