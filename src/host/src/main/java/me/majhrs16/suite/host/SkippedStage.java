package me.majhrs16.suite.host;

/**
 * A stage id listed in the plan that no active module published.
 *
 * <p>The engine records it as {@code SKIPPED} with its reason and continues;
 * an absent stage is never an error and never a silent no-op.</p>
 *
 * @param stageId contract id from the plan
 * @param detail  {@code stage-unpublished} or {@code module-inactive}
 */
public record SkippedStage(String stageId, String detail) {
}
