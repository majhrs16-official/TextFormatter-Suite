package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

import java.util.Collection;
import java.util.Collections;

/**
 * Firewall contract of the iFlow module: routes a message for each recipient
 * based on channel policy and a prioritized rule set.
 *
 * <p>The TextFormatter module renders; iFlow decides <em>if</em>, <em>how</em>
 * and <em>when</em> a delivery happens. Consumers reconcile
 * {@link RouteDecision} targets themselves ({@code LOG} → deliver so the
 * recipient hears it, {@code REDIRECT} → console audit, {@code DROP}/{@code REJECT}
 * → discard, {@code RATE_LIMIT} → retry after {@code backoffMillis}).</p>
 */
public interface Router {

    /**
     * Computes the route decision for a single recipient, returning both the
     * decision and the potentially transformed message.
     */
    RouteOutcome route(Message message, Actor recipient);

    /**
     * Checks if an actor has a permission.
     * Delegates to the PermissionChecker provided at router construction.
     */
    boolean hasPermission(Actor actor, String permission);

    /**
     * @throws UnsupportedOperationException always. iflow VM executes binary programs only.
     * Use {@link me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine#setProgram(ProgramImage)} for hot-reload.
     */
    @Deprecated(forRemoval = true)
    default void setRules(Collection<?> rules) {
        throw new UnsupportedOperationException("iflow VM executes binary .flow programs only. " +
            "Use VmPolicyEngine.setProgram(ProgramImage) for hot-reload.");
    }

    /**
     * @return empty collection. iflow VM executes binary programs only.
     * @throws UnsupportedOperationException always.
     */
    @Deprecated(forRemoval = true)
    default Collection<?> rules() {
        return Collections.emptyList();
    }

    /**
     * Checks rate limit for a message emission (once per message, before fan-out).
     * Returns true if within budget, false if throttled.
     * Should be called BEFORE expanding recipients to avoid consuming budget per recipient.
     */
    boolean checkEmissionRateLimit(Message message);

    /**
     * Gets the wait time until the next rate limit window for a message.
     * Returns nanoseconds until the next window, or 0 if no rate limit applies.
     */
    long nanosUntilNextRateLimitWindow(Message message);
}