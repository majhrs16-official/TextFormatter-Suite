package me.majhrs16.cht.spigot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bukkit.entity.Player;

/**
 * Reads a player's locale across every Spigot version without compiling
 * against any concrete NMS class.
 *
 * <p>{@link Player#getLocale()} has existed since 1.12.2 and is preferred. On
 * older versions it returns the (often empty) default, so we fall back to
 * reading the {@code locale} field off the NMS {@code EntityPlayer} handle
 * through {@code CraftPlayer#getHandle()}. The NMS class and field are located
 * reflectively, so this single class works from 1.8 up to the current
 * versions.</p>
 */
final class NmsLocaleBridge {

    private static final String CRAFT_PLAYER = "org.bukkit.craftbukkit.entity.CraftPlayer";

    private NmsLocaleBridge() {
    }

    static String localeOf(Player player) {
        String locale = bukkitLocale(player);
        if (locale != null && !locale.isEmpty()) {
            return locale;
        }
        return nmsLocale(player);
    }

    /** {@code Player#getLocale()} (1.12.2+); null when the method is missing. */
    private static String bukkitLocale(Player player) {
        try {
            return player.getLocale();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Reads the NMS {@code EntityPlayer#locale} field via reflection. */
    private static String nmsLocale(Player player) {
        try {
            Class<?> craftPlayer = Class.forName(CRAFT_PLAYER);
            Method getHandle = craftPlayer.getMethod("getHandle");
            Object entityPlayer = getHandle.invoke(player);
            if (entityPlayer == null) {
                return null;
            }
            Field locale = findField(entityPlayer.getClass(), "locale");
            if (locale == null) {
                return null;
            }
            Object value = locale.get(entityPlayer);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Searches the type hierarchy for a field by name; null when absent. */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException | SecurityException ignored) {
                // keep climbing
            }
        }
        return null;
    }
}