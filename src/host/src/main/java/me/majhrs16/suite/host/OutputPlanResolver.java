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

    /** Where the effective targets came from. */
    public enum Origin {
        MESSAGE("message"),
        SUITE("suite"),
        MODULE_DEFAULT("module-default");

        private final String wireName;

        Origin(String wireName) {
            this.wireName = wireName;
        }

        /** @return stable token used by the dispatch trace */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * Effective plan plus the precedence level that supplied its targets.
     *
     * @param plan   resolved plan
     * @param origin source of the declared targets
     */
    public record Resolution(OutputPlan plan, Origin origin) {
        public Resolution {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(origin, "origin");
        }
    }

    private OutputPlanResolver() {
    }

    /**
     * @param messagePlan plan carried by the message
     * @param suitePlan   plan read from {@code config/suite/config.yml}
     * @return the effective plan, never {@code null}
     */
    public static OutputPlan resolve(OutputPlan messagePlan, OutputPlan suitePlan) {
        return resolveDetailed(messagePlan, suitePlan).plan();
    }

    /**
     * @param messagePlan plan carried by the message
     * @param suitePlan   plan read from {@code config/suite/config.yml}
     * @return the effective plan and the origin of its targets
     */
    public static Resolution resolveDetailed(OutputPlan messagePlan, OutputPlan suitePlan) {
        Objects.requireNonNull(messagePlan, "messagePlan");
        Objects.requireNonNull(suitePlan, "suitePlan");

        ParallelMode parallel = messagePlan.parallel() != ParallelMode.INHERIT
                ? messagePlan.parallel()
                : suitePlan.parallel();

        if (messagePlan.declared()) {
            return new Resolution(
                    new OutputPlan(true, messagePlan.targets(), parallel), Origin.MESSAGE);
        }
        if (suitePlan.declared()) {
            return new Resolution(
                    new OutputPlan(true, suitePlan.targets(), parallel), Origin.SUITE);
        }
        return new Resolution(
                new OutputPlan(false, List.of(), parallel), Origin.MODULE_DEFAULT);
    }
}
