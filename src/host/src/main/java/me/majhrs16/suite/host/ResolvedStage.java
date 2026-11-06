package me.majhrs16.suite.host;

import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;

import java.util.Objects;
import java.util.Set;

/**
 * A {@link PipelineStage} bound to the module that published it.
 *
 * <p>The engine only sees resolved stages. Missing entries are kept by the
 * plan builder as {@code SKIPPED} diagnostics, so the engine never invokes a
 * stage that does not exist.</p>
 *
 * @param stageId  contract id from the plan
 * @param moduleId module that published the stage
 * @param stage    implementation
 */
public record ResolvedStage(String stageId, String moduleId, PipelineStage stage) {

    public ResolvedStage {
        Objects.requireNonNull(stageId, "stageId");
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(stage, "stage");
    }

    /** @return phases declared by the implementation */
    public Set<PipelineScope> scopes() {
        return stage.scopes();
    }
}
