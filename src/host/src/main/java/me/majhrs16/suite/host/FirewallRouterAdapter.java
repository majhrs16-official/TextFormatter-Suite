package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.RouteOutcome;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.firewall.DefaultRouter;
import me.majhrs16.suite.iflow.target.PolicyTarget;

/**
 * Adapts the legacy {@code iflow-firewall} router to the short-lived
 * {@code iflow-vm} contract used by {@link SuiteHost}.
 *
 * <p>The two iFlow modules are independent and keep their own types. This
 * adapter is the only place that knows both; it lives in the host, which is
 * the integration hub. No module depends on the other.</p>
 */
final class FirewallRouterAdapter implements Router, AutoCloseable {

    private final DefaultRouter delegate;

    FirewallRouterAdapter(DefaultRouter delegate) {
        this.delegate = delegate;
    }

    @Override
    public RouteOutcome route(Message message, Actor recipient) {
        me.majhrs16.suite.iflow.firewall.RouteOutcome legacy = delegate.route(message, recipient);
        me.majhrs16.suite.iflow.firewall.RouteDecision decision = legacy.decision();

        RouteDecision adapted = new RouteDecision(
            PolicyTarget.valueOf(decision.target().name()),
            decision.reason(),
            decision.backoffMillis(),
            decision.recipient(),
            decision.emitter(),
            decision.redirectChannel()
        );
        return new RouteOutcome(adapted, legacy.message());
    }

    @Override
    public boolean hasPermission(Actor actor, String permission) {
        return delegate.hasPermission(actor, permission);
    }

    @Override
    public boolean checkEmissionRateLimit(Message message) {
        return delegate.checkEmissionRateLimit(message);
    }

    @Override
    public long nanosUntilNextRateLimitWindow(Message message) {
        return delegate.nanosUntilNextRateLimitWindow(message);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
