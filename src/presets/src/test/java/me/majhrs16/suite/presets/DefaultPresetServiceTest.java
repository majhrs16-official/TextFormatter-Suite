package me.majhrs16.suite.presets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPresetServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsBuiltInPresetAndReloadsAfterSuccess() {
        PresetManager manager = new PresetManager(tempDir.resolve("presets"));
        DefaultPresetService service = new DefaultPresetService(manager);
        AtomicReference<String> reloaded = new AtomicReference<>();

        assertThat(service.apply("standard", tempDir, reloaded::set)).isTrue();

        assertThat(reloaded.get()).isEqualTo("standard");
        assertThat(tempDir.resolve("config/suite/config.yml")).exists();
        assertThat(tempDir.resolve("config/textformatter/channels/chat.global.yml"))
            .exists();
    }

    @Test
    void unknownPresetDoesNotReload() {
        PresetManager manager = new PresetManager(tempDir.resolve("presets"));
        DefaultPresetService service = new DefaultPresetService(manager);
        AtomicReference<String> reloaded = new AtomicReference<>();

        assertThat(service.apply("does-not-exist", tempDir, reloaded::set)).isFalse();

        assertThat(reloaded.get()).isNull();
        assertThat(tempDir.resolve("config/suite/config.yml")).doesNotExist();
    }

    @Test
    void listsCatalogIdentifiers() {
        PresetManager manager = new PresetManager(tempDir.resolve("presets"));
        DefaultPresetService service = new DefaultPresetService(manager);

        assertThat(service.list()).contains("standard", "rpg");
    }
}
