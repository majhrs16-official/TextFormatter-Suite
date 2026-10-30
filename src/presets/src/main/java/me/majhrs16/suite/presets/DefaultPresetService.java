package me.majhrs16.suite.presets;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import me.majhrs16.suite.api.spi.PresetService;

/**
 * Applies catalog presets through the canonical {@link FilePresetTarget} and
 * invokes the host reload callback only after a successful write.
 */
public final class DefaultPresetService implements PresetService {

    private final PresetManager manager;

    public DefaultPresetService(PresetManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public List<String> list() {
        return manager.listAll().stream().map(Preset::id).toList();
    }

    @Override
    public boolean apply(String presetId, Path suiteDir, Consumer<String> reload) {
        Objects.requireNonNull(presetId, "presetId");
        Objects.requireNonNull(suiteDir, "suiteDir");
        Objects.requireNonNull(reload, "reload");
        boolean applied = manager.apply(presetId, new FilePresetTarget(suiteDir));
        if (applied) {
            reload.accept(presetId);
        }
        return applied;
    }
}
