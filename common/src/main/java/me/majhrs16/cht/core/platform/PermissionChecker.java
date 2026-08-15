package me.majhrs16.cht.core.platform;

import me.majhrs16.cht.core.player.Subject;

/**
 * Port for permission checks. Kept minimal on purpose: the engine only needs
 * to know whether a subject may perform an action, not how the platform
 * resolves it (Spigot permissions, Fabric permission API, or a no-op).
 */
public interface PermissionChecker {

    /**
     * @param subject the subject to check.
     * @param node    a dotted permission node, e.g. {@code cht.chat.color}.
     * @return true if the subject is allowed to perform the action.
     */
    boolean has(Subject subject, String node);
}