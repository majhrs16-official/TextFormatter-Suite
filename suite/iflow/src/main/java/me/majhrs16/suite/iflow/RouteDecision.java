package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.iflow.target.PolicyTarget;

/**
 * Immutable outcome of a single {@code (message × recipient)} route decision.
 *
 * @param target    the disposition applied to this recipient.
 * @param reason    human readable cause, present on non-{@code LOG} targets.
 * @param backoffMillis minimum delay before the recipient may be retried when
 *                 rate-limited; zero otherwise.
 * @param recipient the receiver the decision was computed for.
 * @param emitter   the sender that emitted the message.
 */
public record RouteDecision(
        PolicyTarget target,
        String reason,
        long backoffMillis,
        Actor recipient,
        Actor emitter
) {

    public boolean delivered() {
        return target == PolicyTarget.LOG
            || target == PolicyTarget.REDIRECT;
    }

    public boolean rejected() {
        return target == PolicyTarget.REJECT;
    }

    /** Renders a compact one-line description for logs and tooltips. */
    public String describe() {
        return target + (reason == null ? "" : " (" + reason + ")");
    }
}