package me.majhrs16.suite.iflow.hub;

import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.StageResult;

import java.util.Objects;
import java.util.Set;

/**
 * {@code route} stage: evaluates the iFlow policy once per emission for the
 * rate limit and once per recipient for the actual decision.
 *
 * <p>A legacy {@code CHANNEL_REDIRECT} decision triggers exactly one secondary
 * routing pass on the redirected channel. A second channel mutation is a
 * {@code DECISION_CONFLICT} and the unit is rejected; there is no recursive
 * re-entry into later stages.</p>
 *
 * <p>When no iFlow hub is installed this stage is not published: the engine
 * expands recipients and delivers, which is the documented default allow.</p>
 */
public final class RouteStage implements PipelineStage {

    /** Contract id of the route stage. */
    public static final String ID = "route";

    private final Router router;

    public RouteStage(Router router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<PipelineScope> scopes() {
        return Set.of(PipelineScope.MESSAGE_PRE, PipelineScope.RECIPIENT);
    }

    @Override
    public StageResult beforeMessage(PipelineMessage message, PipelineContext context) {
        Message current = message.message();
        if (router.checkEmissionRateLimit(current)) {
            return StageResult.continueWith(message);
        }
        long waitMillis = router.nanosUntilNextRateLimitWindow(current) / 1_000_000L;
        return StageResult.drop(message, "rate limit exceeded: " + waitMillis + "ms");
    }

    @Override
    public StageResult perRecipient(PipelineMessage unit, PipelineContext context) {
        Actor recipient = unit.recipient();
        if (recipient == null) {
            return StageResult.error(unit, "route requires a recipient");
        }

        RouteOutcome first = router.route(unit.message(), recipient);
        if (first == null || first.decision() == null || first.message() == null) {
            return StageResult.error(unit, "router returned an incomplete outcome");
        }
        RouteDecision decision = first.decision();
        Message message = first.message();

        if (decision.target() == PolicyTarget.CHANNEL_REDIRECT) {
            String target = decision.redirectChannel();
            if (target == null || target.isBlank()) {
                return StageResult.reject(unit.withMessage(message),
                        "CHANNEL_REDIRECT without a target channel");
            }
            RouteOutcome second = router.route(message.withChannel(target), recipient);
            if (second == null || second.decision() == null || second.message() == null) {
                return StageResult.error(unit.withMessage(message),
                        "router returned an incomplete secondary outcome");
            }
            if (second.decision().target() == PolicyTarget.CHANNEL_REDIRECT) {
                return StageResult.reject(unit.withMessage(message), "DECISION_CONFLICT");
            }
            decision = second.decision();
            message = second.message();
        }

        return StageResult.continueWith(unit.withMessage(message).withDecision(decision));
    }
}
