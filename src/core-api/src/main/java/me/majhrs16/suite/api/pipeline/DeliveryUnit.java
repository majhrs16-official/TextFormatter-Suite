package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

import java.util.Objects;

/**
 * One rendered unit ready to be delivered to one recipient.
 *
 * <p>The unit carries the recipient-specific rendered output produced by the
 * {@code format} stage, the routing decision that applies to it and the
 * per-recipient message produced by the {@code route} stage. It is neutral: the
 * local sink receives it and delegates the physical delivery to the
 * {@code LocalDelivery} port, so no platform type crosses this boundary.</p>
 *
 * <p>{@code message} is not necessarily the envelope message: routing may
 * transform the channel, the sounds or the text for one recipient.</p>
 *
 * @param recipient the recipient of this rendered unit
 * @param decision  the routing decision, or {@code null} when none applied
 * @param message   the per-recipient message that produced {@code rendered}
 * @param rendered  the rendered output for this recipient
 */
public record DeliveryUnit(
        Actor recipient,
        RouteDecision decision,
        Message message,
        RenderedOutput rendered
) {

    public DeliveryUnit {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(rendered, "rendered");
    }
}
