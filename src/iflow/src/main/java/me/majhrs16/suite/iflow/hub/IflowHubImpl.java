package me.majhrs16.suite.iflow.hub;

import java.util.List;
import java.util.Objects;

import me.majhrs16.suite.api.iflow.IflowBackend;
import me.majhrs16.suite.api.iflow.IflowHub;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

/**
 * Implementación del hub. Delega en el {@link Router} del backend activo y
 * falla cerrado cuando no hay ninguno.
 */
final class IflowHubImpl implements IflowHub {

    private final IflowBackendManager manager;

    IflowHubImpl(IflowBackendManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public Router router() {
        IflowBackend backend = manager.active();
        return backend == null ? null : backend.router();
    }

    @Override
    public String activeBackend() {
        IflowBackend backend = manager.active();
        return backend == null ? "none" : backend.name();
    }

    @Override
    public List<String> availableBackends() {
        return manager.availableBackends();
    }

    @Override
    public boolean reload() {
        manager.refresh();
        return manager.active() != null;
    }

    @Override
    public RouteOutcome route(Message message, Actor recipient) {
        Router router = router();
        if (router == null) {
            Actor emitter = message.sender() == null ? Actor.unknown("console") : message.sender();
            RouteDecision decision = new RouteDecision(
                PolicyTarget.REJECT,
                "no iflow backend available",
                0,
                recipient,
                emitter
            );
            return RouteOutcome.unchanged(decision, message);
        }
        return router.route(message, recipient);
    }

    @Override
    public boolean hasPermission(Actor actor, String permission) {
        Router router = router();
        return router != null && router.hasPermission(actor, permission);
    }

    @Override
    public boolean checkEmissionRateLimit(Message message) {
        Router router = router();
        return router != null && router.checkEmissionRateLimit(message);
    }

    @Override
    public long nanosUntilNextRateLimitWindow(Message message) {
        Router router = router();
        return router == null ? 0 : router.nanosUntilNextRateLimitWindow(message);
    }

    String lastError() {
        return manager.lastError();
    }
}
