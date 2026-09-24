package me.majhrs16.suite.spigothost.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

/**
 * Caches NMS reflection lookups for player locale resolution.
 * <p>
 * {@link Player#getLocale()} was added in 1.12.2 but returns the often-empty
 * default on older Spigot versions. This bridge falls back to reading the
 * {@code locale} field from the NMS {@code EntityPlayer} handle via
 * {@code CraftPlayer#getHandle()}. The NMS class, method and field are
 * resolved reflectively once and cached in per-player maps so the chat hot
 * path never re-triggers the lookup.
 */
public final class NmsLocaleBridge {

    private static final String CRAFT_PLAYER = "org.bukkit.craftbukkit.entity.CraftPlayer";

    private static volatile Method getHandleMethod;
    private static volatile boolean getHandleResolved;

    private static final Map<Class<?>, Field> LOCALE_FIELDS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LOCALE_CACHE = new ConcurrentHashMap<>();

    private static final Logger LOGGER = Logger.getLogger("TextFormatterSuite");

    private NmsLocaleBridge() {
    }

    static void init() {
        LOCALE_CACHE.clear();
    }

    /**
     * Returns the player's locale, preferring Bukkit's {@code getLocale()} and
     * falling back to NMS reflection with caching.
     */
    public static String localeOf(Player player) {
        String locale = bukkitLocale(player);
        if (locale != null && !locale.isEmpty()) {
            return locale;
        }
        UUID uuid = player.getUniqueId();
        String cached = LOCALE_CACHE.get(uuid);
        if (cached != null) {
            return cached;
        }
        String nms = nmsLocale(player);
        LOCALE_CACHE.put(uuid, nms == null ? "" : nms);
        return nms;
    }

    /** {@code Player#getLocale()} (1.12.2+); null when the method is missing or empty. */
    private static String bukkitLocale(Player player) {
        try {
            return player.getLocale();
        } catch (Throwable e) {
            LOGGER.finer("getLocale() failed: " + e.getMessage());
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
            LOGGER.finer("NMS locale lookup failed: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
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
                LOGGER.finer("CraftPlayer#getHandle not resolvable: " + e.getMessage());
                getHandleMethod = null;
            } finally {
                getHandleResolved = true;
            }
            return getHandleMethod;
        }
    }

    /** Searches the type hierarchy for a field named "locale"; null when absent. */
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