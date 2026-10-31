package me.majhrs16.suite.syncbukkit;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import me.majhrs16.suite.synccommon.SyncYaml;

/**
 * Configuration of the Bukkit/Paper event capture package.
 *
 * @param enabled          whether the package registers its listeners
 * @param claimMode        how vanilla chat is suppressed
 * @param logChatToConsole whether the console receives an extra chat copy
 */
public record SyncBukkitConfig(boolean enabled, ClaimMode claimMode, boolean logChatToConsole) {

    public static final ClaimMode DEFAULT_CLAIM_MODE = ClaimMode.CANCEL_EVENT;

    public SyncBukkitConfig {
        claimMode = claimMode == null ? DEFAULT_CLAIM_MODE : claimMode;
    }

    public static SyncBukkitConfig defaults() {
        return new SyncBukkitConfig(true, DEFAULT_CLAIM_MODE, true);
    }

    public static SyncBukkitConfig loadFromConfigDir(Path configDir) {
        Map<String, Object> map = SyncYaml.readMap(configDir.resolve("config.yml"));
        return new SyncBukkitConfig(
                SyncYaml.bool(map, "enabled", true),
                ClaimMode.parse(SyncYaml.string(map, "claim-mode", "cancel-event")),
                SyncYaml.bool(map, "log-chat-to-console", true));
    }

    /** How the adapter takes ownership of the vanilla chat event. */
    public enum ClaimMode {
        /** Cancel the event; the suite owns every copy. */
        CANCEL_EVENT,
        /** Leave the event alive but clear its recipients. */
        CLEAR_RECIPIENTS,
        /** Do not suppress vanilla chat. */
        NONE;

        public static ClaimMode parse(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT_CLAIM_MODE;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            return switch (normalized) {
                case "clear-recipients", "clear" -> CLEAR_RECIPIENTS;
                case "none", "off" -> NONE;
                default -> CANCEL_EVENT;
            };
        }
    }
}
