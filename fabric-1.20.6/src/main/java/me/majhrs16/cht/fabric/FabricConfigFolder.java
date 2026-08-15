package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.platform.ConfigFolder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;

/**
 * Fabric {@link ConfigFolder} rooted in {@code config/chattranslator}.
 */
final class FabricConfigFolder implements ConfigFolder {

    private final File dataFolder;

    FabricConfigFolder() {
        this.dataFolder = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("chattranslator")
            .toFile();
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