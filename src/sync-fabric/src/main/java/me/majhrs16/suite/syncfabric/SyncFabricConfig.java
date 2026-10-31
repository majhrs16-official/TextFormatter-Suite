package me.majhrs16.suite.syncfabric;

import java.nio.file.Path;
import java.util.Map;

import me.majhrs16.suite.synccommon.SyncYaml;

/**
 * Configuration of the Fabric event capture package.
 *
 * <p>Fabric API does not expose a cancellable chat event, so this package has
 * no claim mode: vanilla chat remains visible unless another Fabric mod
 * suppresses it. The suite still owns routing, formatting and delivery.</p>
 *
 * @param enabled          whether the package registers its callbacks
 * @param logChatToConsole whether the console receives an extra chat copy
 */
public record SyncFabricConfig(boolean enabled, boolean logChatToConsole) {

    public static SyncFabricConfig defaults() {
        return new SyncFabricConfig(true, true);
    }

    public static SyncFabricConfig loadFromConfigDir(Path configDir) {
        Map<String, Object> map = SyncYaml.readMap(configDir.resolve("config.yml"));
        return new SyncFabricConfig(
                SyncYaml.bool(map, "enabled", true),
                SyncYaml.bool(map, "log-chat-to-console", true));
    }
}
