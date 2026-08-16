package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.rule.Rule;

import java.util.Collection;

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
     * Computes the route decision for a single recipient.
     */
    RouteDecision route(Message message, Actor recipient);

    /** Replaces the active rule set (hot reload of {@code rules.yml}). */
    void setRules(Collection<Rule> rules);

    /** @return the active rule set in evaluation order. */
    Collection<Rule> rules();
}