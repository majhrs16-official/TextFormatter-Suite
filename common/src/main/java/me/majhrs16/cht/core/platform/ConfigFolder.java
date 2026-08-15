package me.majhrs16.cht.core.platform;

import java.io.File;

/**
 * Port over where the plugin is allowed to store files.
 *
 * <p>Spigot maps {@code dataFolder()} to the plugin's folder inside
 * {@code plugins/}; Fabric maps it to {@code config/chattranslator}.</p>
 */
public interface ConfigFolder {

    /**
     * @return the writable root folder for plugin data.
     */
    File dataFolder();

    /**
     * @return the {@code config.yml} file (may not exist yet).
     */
    File configFile();

    /**
     * @return the {@code formats.yml} file (may not exist yet).
     */
    File formatsFile();

    /**
     * @return the {@code storage.yml} / user-data file.
     */
    File storageFile();

    /**
     * @return the {@code rules.yml} file (may not exist yet).
     */
    File rulesFile();
}