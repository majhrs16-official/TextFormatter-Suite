package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.target.PolicyTarget;

import net.kyori.adventure.text.Component;

/**
 * Result of running one {@code (message × recipient)} through the full
 * pipeline: iFlow decides, TextFormatter renders.
 *
 * @param decision the firewall disposition.
 * @param rendered the ready-to-send component, empty unless delivered.
 * @param message the (potentially transformed) message used for rendering and delivery context.
 * @param redirect when the decision is {@link PolicyTarget#REDIRECT}, the
 *                 message goes to the console instead of the recipient.
 */
public record RoutingResult(
        RouteDecision decision,
        Component rendered,
        Message message,
        boolean redirect
) {

    /** The message should be sent to the recipient. */
    public boolean delivered() {
        return decision != null && decision.delivered();
    }

    /** The message was discarded or throttled and must not be shown. */
    public boolean silenced() {
        return decision != null && !decision.delivered();
    }

    /** Compatibility constructor for existing code. */
    public RoutingResult(RouteDecision decision, Component rendered, boolean redirect) {
        this(decision, rendered, null, redirect);
    }
}