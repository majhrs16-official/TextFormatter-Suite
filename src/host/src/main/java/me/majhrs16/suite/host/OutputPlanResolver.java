package me.majhrs16.suite.host;

import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.ParallelMode;

import java.util.List;
import java.util.Objects;

/**
 * Resolves the effective output plan for one emission.
 *
 * <p>Precedence is message → suite configuration → multiplexer default. The
 * multiplexer default is represented by {@link OutputPlan#inherit()}: a plan
 * that declares nothing and lets each stage apply its own behaviour. Targets
 * and finalization mode are resolved independently, so a message can override
 * the targets while inheriting {@code outputs-parallel}, or vice versa.</p>
 */
public final class OutputPlanResolver {

    private OutputPlanResolver() {
    }

    /**
     * @param messagePlan plan carried by the message
     * @param suitePlan   plan read from {@code config/suite/config.yml}
     * @return the effective plan, never {@code null}
     */
    public static OutputPlan resolve(OutputPlan messagePlan, OutputPlan suitePlan) {
        Objects.requireNonNull(messagePlan, "messagePlan");
        Objects.requireNonNull(suitePlan, "suitePlan");

        ParallelMode parallel = messagePlan.parallel() != ParallelMode.INHERIT
                ? messagePlan.parallel()
                : suitePlan.parallel();

        if (messagePlan.declared()) {
            return new OutputPlan(true, messagePlan.targets(), parallel);
        }
        if (suitePlan.declared()) {
            return new OutputPlan(true, suitePlan.targets(), parallel);
        }
        return new OutputPlan(false, List.of(), parallel);
    }
}
