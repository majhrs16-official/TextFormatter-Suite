package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.platform.ConfigFolder;

import java.io.File;

/**
 * Spigot {@link ConfigFolder}: the plugin's data folder inside {@code plugins/}.
 */
final class SpigotConfigFolder implements ConfigFolder {

    private final File dataFolder;

    SpigotConfigFolder(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    @Override
    public File dataFolder() {
        return dataFolder;
    }

    @Override
    public File configFile() {
        return new File(dataFolder, "config.yml");
    }

    @Override
    public File formatsFile() {
        return new File(dataFolder, "formats.yml");
    }

    @Override
    public File storageFile() {
        return new File(dataFolder, "users.yml");
    }

    @Override
    public File rulesFile() {
        return new File(dataFolder, "rules.yml");
    }
}