package me.majhrs16.suite.api.iflow;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

/**
 * Shared routing contract between the platform host and the iFlow engines.
 *
 * <p>The host routes each message for each recipient through the installed
 * engine. Engine-specific configuration (firewall rules, VM programs) stays in
 * the engine package; this contract only exposes the operations every engine
 * must provide.</p>
 */
public interface Router {

    /**
     * Computes the route decision for a single recipient, returning both the
     * decision and the potentially transformed message.
     */
    RouteOutcome route(Message message, Actor recipient);

    /** @return whether {@code actor} possesses {@code permission}. */
    boolean hasPermission(Actor actor, String permission);

    /**
     * Checks the emission-level rate limit once per message, before fan-out.
     *
     * @return {@code true} when the message is within budget
     */
    boolean checkEmissionRateLimit(Message message);

    /**
     * @return nanoseconds until the next rate-limit window, or {@code 0} when
     *         no rate limit applies
     */
    long nanosUntilNextRateLimitWindow(Message message);
}
