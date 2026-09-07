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
 * @param redirectChannel target channel for CHANNEL_REDIRECT (may be null).
 */
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
 * @param redirectChannel target channel for CHANNEL_REDIRECT (may be null).
 */
public record RouteDecision(
        PolicyTarget target,
        String reason,
        long backoffMillis,
        Actor recipient,
        Actor emitter,
        String redirectChannel
) {

    // Compact constructor for backwards compatibility (redirectChannel defaults to null)
    public RouteDecision(PolicyTarget target, String reason, long backoffMillis,
                         Actor recipient, Actor emitter) {
        this(target, reason, backoffMillis, recipient, emitter, null);
    }

    public boolean delivered() {
        return target == PolicyTarget.LOG
            || target == PolicyTarget.REDIRECT
            || target == PolicyTarget.CHANNEL_REDIRECT;
    }

    public boolean rejected() {
        return target == PolicyTarget.REJECT;
    }

    /** Renders a compact one-line description for logs and tooltips. */
    public String describe() {
        String base = target + (reason == null ? "" : " (" + reason + ")");
        if (target == PolicyTarget.CHANNEL_REDIRECT && redirectChannel != null) {
            return base + " → " + redirectChannel;
        }
        return base;
    }
}