package me.majhrs16.suite.api.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Neutral port for applying a named preset to the suite file layout.
 *
 * <p>The {@code presets} package owns the catalog and the application
 * algorithm. The platform bootstrap supplies the suite data directory and the
 * callback that reloads the runtime after a successful application, so the
 * host never imports the preset implementation.</p>
 */
public interface PresetService {

    /** @return identifiers of the presets currently available. */
    List<String> list();

    /**
     * Applies {@code presetId} under {@code suiteDir}.
     *
     * <p>The reload callback runs only after the preset was persisted
     * successfully; an unknown preset returns {@code false} without invoking
     * it.</p>
     *
     * @param presetId preset identifier
     * @param suiteDir suite data directory
     * @param reload   runtime reload callback
     * @return {@code true} when the preset was applied
     */
    boolean apply(String presetId, Path suiteDir, Consumer<String> reload);
}
