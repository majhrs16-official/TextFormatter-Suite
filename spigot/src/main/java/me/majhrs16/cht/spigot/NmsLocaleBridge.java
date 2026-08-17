package me.majhrs16.cht.spigot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

/**
 * Reads a player's locale across every Spigot version without compiling
 * against any concrete NMS class.
 *
 * <p>{@link Player#getLocale()} has existed since 1.12.2 and is preferred. On
 * older versions it returns the (often empty) default, so we fall back to
 * reading the {@code locale} field off the NMS {@code EntityPlayer} handle
 * through {@code CraftPlayer#getHandle()}. The NMS class, method and field are
 * located reflectively once and cached, matching the per-player locale by a
 * short-lived map so the chat hot path never re-triggers the lookup.</p>
 */
final class NmsLocaleBridge {

    private static final String CRAFT_PLAYER = "org.bukkit.craftbukkit.entity.CraftPlayer";

    private static volatile Method getHandleMethod;
    private static volatile boolean getHandleResolved;

    private static final Map<Class<?>, Field> LOCALE_FIELDS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> CACHE = new ConcurrentHashMap<>();

    private static Logger logger;

    private NmsLocaleBridge() {
    }

    static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    static String localeOf(Player player) {
        String locale = bukkitLocale(player);
        if (locale != null && !locale.isEmpty()) {
            return locale;
        }
        UUID uuid = player.getUniqueId();
        String cached = CACHE.get(uuid);
        if (cached != null) {
            return cached;
        }
        String nms = nmsLocale(player);
        CACHE.put(uuid, nms == null ? "" : nms);
        return nms;
    }

    /** {@code Player#getLocale()} (1.12.2+); null when the method is missing. */
    private static String bukkitLocale(Player player) {
        try {
            return player.getLocale();
        } catch (Throwable e) {
            if (logger != null) {
                logger.log(Level.FINER, "getLocale() failed", e);
            }
            return null;
        }
    }

    /** Reads the NMS {@code EntityPlayer#locale} field via cached reflection. */
    private static String nmsLocale(Player player) {
        try {
            Method getHandle = resolveGetHandle();
            if (getHandle == null) {
                return null;
            }
            Object entityPlayer = getHandle.invoke(player);
            if (entityPlayer == null) {
                return null;
            }
            Field locale = LOCALE_FIELDS.computeIfAbsent(entityPlayer.getClass(),
                NmsLocaleBridge::findField);
            if (locale == null) {
                return null;
            }
            Object value = locale.get(entityPlayer);
            return value == null ? null : value.toString();
        } catch (Throwable e) {
            if (logger != null) {
                logger.log(Level.FINER, "NMS locale lookup failed", e);
            }
            return null;
        }
    }

    private static Method resolveGetHandle() {
        if (getHandleResolved) {
            return getHandleMethod;
        }
        synchronized (NmsLocaleBridge.class) {
            if (getHandleResolved) {
                return getHandleMethod;
            }
            try {
                Class<?> craftPlayer = Class.forName(CRAFT_PLAYER);
                getHandleMethod = craftPlayer.getMethod("getHandle");
            } catch (ReflectiveOperationException e) {
                if (logger != null) {
                    logger.log(Level.FINER, "CraftPlayer#getHandle not resolvable", e);
                }
                getHandleMethod = null;
            } finally {
                getHandleResolved = true;
            }
            return getHandleMethod;
        }
    }

    /** Searches the type hierarchy for a field by name; null when absent. */
    private static Field findField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField("locale");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException | SecurityException ignored) {
                // keep climbing
            }
        }
        return null;
    }
}