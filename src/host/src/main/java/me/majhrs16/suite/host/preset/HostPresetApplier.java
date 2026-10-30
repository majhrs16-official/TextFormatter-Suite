package me.majhrs16.suite.host.preset;

import me.majhrs16.suite.presets.FilePresetTarget;
import me.majhrs16.suite.presets.PresetManager;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Host-side preset adapter.
 *
 * <p>Selects a preset from the {@link PresetManager}, persists it through the
 * canonical {@link FilePresetTarget}, and only then invokes the runtime reload
 * callback. The preset package owns the application algorithm; this adapter
 * owns the host files and the reload boundary.</p>
 */
public final class HostPresetApplier {

    private final PresetManager manager;
    private final FilePresetTarget target;
    private final Consumer<String> reload;

    public HostPresetApplier(PresetManager manager, Path suiteDir,
                             Consumer<String> reload) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.target = new FilePresetTarget(suiteDir);
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    /**
     * Applies {@code presetId} and reloads the host after a successful apply.
     *
     * @return {@code false} when the preset does not exist; the host is not
     *         reloaded in that case
     */
    public boolean apply(String presetId) {
        Objects.requireNonNull(presetId, "presetId");
        boolean applied = manager.apply(presetId, target);
        if (applied) {
            reload.accept(presetId);
        }
        return applied;
    }
}
