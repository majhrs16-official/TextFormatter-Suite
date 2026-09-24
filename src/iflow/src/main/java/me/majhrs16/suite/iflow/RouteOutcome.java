package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.target.PolicyTarget;

/**
 * Result of a routing evaluation, including the potentially transformed message.
 *
 * <p>Since {@link Message} is immutable, transforms applied during routing produce
 * a new message instance that must be used for subsequent rendering and delivery.
 * This record carries both the routing decision and the message to use downstream.</p>
 */
public record RouteOutcome(
        RouteDecision decision,
        Message message
) {

    public boolean delivered() {
        return decision.delivered();
    }

    public boolean redirected() {
        return decision.target() == PolicyTarget.REDIRECT;
    }

    public static RouteOutcome of(RouteDecision decision, Message message) {
        return new RouteOutcome(decision, message);
    }

    public static RouteOutcome unchanged(RouteDecision decision, Message original) {
        return new RouteOutcome(decision, original);
    }
}