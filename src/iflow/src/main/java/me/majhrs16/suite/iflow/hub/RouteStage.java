package me.majhrs16.suite.iflow.hub;

import me.majhrs16.suite.api.iflow.IflowHub;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.StageResult;

import java.util.List;
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
 * <p>The stage owns the iFlow backends. When the effective {@link OutputPlan}
 * declares a {@code sync.<backend>} target whose backend is an available iFlow
 * engine, the stage resolves that backend through the hub and routes with it;
 * without an owned target the hub applies its configured default and fallback.
 * More than one owned target is a conflict, and a backend that is listed but
 * has no router is an explicit error.</p>
 *
 * <p>When no iFlow hub is installed this stage is not published: the engine
 * expands recipients and delivers, which is the documented default allow.</p>
 */
public final class RouteStage implements PipelineStage {

    /** Contract id of the route stage. */
    public static final String ID = "route";

    private final IflowHub hub;

    public RouteStage(IflowHub hub) {
        this.hub = Objects.requireNonNull(hub, "hub");
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
    public boolean ownsOutput(String backend) {
        return backend != null && hub.availableBackends().contains(backend);
    }

    @Override
    public StageResult beforeMessage(PipelineMessage message, PipelineContext context) {
        Selection selection = selectBackend(context.outputs());
        if (selection.error() != null) {
            return StageResult.error(message, selection.error());
        }
        Router router = selection.router() == null ? hub : selection.router();
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

        Selection selection = selectBackend(context.outputs());
        if (selection.error() != null) {
            return StageResult.error(unit, selection.error());
        }
        Router router = selection.router() == null ? hub : selection.router();

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

        PipelineMessage resolved = unit.withMessage(message).withDecision(decision);
        return switch (decision.target()) {
            case DROP -> StageResult.drop(resolved, decision.describe());
            case REJECT -> StageResult.reject(resolved, decision.describe());
            default -> StageResult.continueWith(resolved);
        };
    }

    /**
     * Resolves the backend requested by the effective output plan.
     *
     * <p>Only targets owned by this stage are considered; the engine already
     * rejected an unknown or ambiguous backend. A plan without owned targets
     * keeps the hub default. A declared target must name exactly one installed
     * and available backend.</p>
     */
    private Selection selectBackend(OutputPlan outputs) {
        List<String> backends = declaredBackends(outputs.targets()).stream()
                .filter(this::ownsOutput)
                .toList();
        if (backends.size() > 1) {
            return Selection.error("CONFLICT: multiple iFlow output targets");
        }
        if (backends.isEmpty()) {
            return Selection.inherited();
        }
        String backend = backends.get(0);
        Router selected = hub.router(backend);
        if (selected == null) {
            return Selection.error("iFlow backend '" + backend + "' is unavailable");
        }
        return Selection.of(selected);
    }

    /** @return backend names named by the declared targets, in order */
    static List<String> declaredBackends(List<String> targets) {
        return targets.stream()
                .map(OutputPlan::backend)
                .filter(backend -> !backend.isBlank())
                .toList();
    }

    private record Selection(Router router, String error) {
        static Selection inherited() {
            return new Selection(null, null);
        }

        static Selection of(Router router) {
            return new Selection(Objects.requireNonNull(router, "router"), null);
        }

        static Selection error(String detail) {
            return new Selection(null, detail);
        }
    }
}
