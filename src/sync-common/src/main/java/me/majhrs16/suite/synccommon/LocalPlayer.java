package me.majhrs16.suite.synccommon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Neutral snapshot of an online player exposed by a local backend surface.
 *
 * @param uuid  platform UUID as a string, non-blank
 * @param name  player name, non-blank
 * @param world current world name; {@code null} when the platform has no world
 */
public record LocalPlayer(String uuid, String name, String world) {

    public LocalPlayer {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("uuid must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** @return a map safe to return as a neutral action value */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("uuid", uuid);
        value.put("name", name);
        value.put("world", world);
        return value;
    }
}
