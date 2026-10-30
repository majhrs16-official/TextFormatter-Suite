package me.majhrs16.suite.host.preset;

import me.majhrs16.suite.presets.PresetManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HostPresetApplierTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsBuiltInPresetAndReloadsAfterSuccess() {
        PresetManager manager = new PresetManager(tempDir.resolve("presets"));
        AtomicReference<String> reloaded = new AtomicReference<>();
        HostPresetApplier applier = new HostPresetApplier(
            manager, tempDir, reloaded::set);

        assertThat(applier.apply("standard")).isTrue();

        assertThat(reloaded.get()).isEqualTo("standard");
        assertThat(tempDir.resolve("config/suite/config.yml")).exists();
        assertThat(tempDir.resolve("config/textformatter/channels/chat.global.yml"))
            .exists();
    }

    @Test
    void unknownPresetDoesNotReload() {
        PresetManager manager = new PresetManager(tempDir.resolve("presets"));
        AtomicReference<String> reloaded = new AtomicReference<>();
        HostPresetApplier applier = new HostPresetApplier(
            manager, tempDir, reloaded::set);

        assertThat(applier.apply("does-not-exist")).isFalse();

        assertThat(reloaded.get()).isNull();
        assertThat(tempDir.resolve("config/suite/config.yml")).doesNotExist();
    }
}
