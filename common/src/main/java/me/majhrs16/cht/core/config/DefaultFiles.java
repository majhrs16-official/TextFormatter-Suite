package me.majhrs16.cht.core.config;

import me.majhrs16.cht.core.platform.ConfigFolder;
import me.majhrs16.cht.core.platform.PluginLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Writes the bundled default files ({@code config.yml}, {@code formats.yml})
 * verbatim -- comments included -- the first time the plugin runs.
 *
 * <p>Defaults ship inside the jar as plain YAML resources; this class copies
 * them byte-for-byte when the target does not exist, so every comment and
 * blank line survives. It never overwrites a file the user already wrote.</p>
 */
public final class DefaultFiles {

    private static final String[] FILES = { "config.yml", "formats.yml", "rules.yml" };

    private DefaultFiles() {
    }

    /**
     * Ensures every bundled default exists under the data folder.
     *
     * @param folder platform data folder.
     * @param logger sink for warnings about missing resources.
     */
    public static void copyMissing(ConfigFolder folder, PluginLogger logger) {
        for (String name : FILES) {
            File target = new File(folder.dataFolder(), name);
            if (target.exists()) {
                continue;
            }
            try (InputStream input = DefaultFiles.class.getResourceAsStream("/" + name)) {
                if (input == null) {
                    logger.warn("Bundled default '%s' is missing from the jar", name);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.debug("Wrote default %s", target);
            } catch (IOException e) {
                logger.error("Could not write default %s: %s", name, e.getMessage());
            }
        }
    }
}