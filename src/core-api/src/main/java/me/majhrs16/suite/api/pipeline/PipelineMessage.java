package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

import java.util.List;
import java.util.Objects;

/**
 * One unit travelling through the pipeline.
 *
 * <p>The same {@link Message} can be transformed by a stage; the record is
 * immutable, so a stage returns a new instance through the {@code with*}
 * helpers. {@code recipient} is {@code null} outside {@link
 * PipelineScope#RECIPIENT}; {@code decision} is {@code null} when no
 * {@code route} stage ran; {@code rendered} is {@code null} until the
 * {@code format} stage produces it.</p>
 *
 * <p>{@code deliveries} is filled by the engine after the recipient phase and
 * carried into {@link PipelineScope#MESSAGE_POST}: it is the envelope the
 * {@code sync} stage hands to the sinks. It is empty outside that phase.</p>
 *
 * @param message    the message content and routing direction
 * @param recipient  the recipient of this unit, or {@code null}
 * @param decision   the routing decision, or {@code null}
 * @param rendered   the rendered output, or {@code null}
 * @param cancelEvent whether the originating platform event must be cancelled
 * @param deliveries rendered units collected for the post phase
 */
public record PipelineMessage(
        Message message,
        Actor recipient,
        RouteDecision decision,
        RenderedOutput rendered,
        boolean cancelEvent,
        List<DeliveryUnit> deliveries
) {

    public PipelineMessage {
        Objects.requireNonNull(message, "message");
        deliveries = List.copyOf(Objects.requireNonNull(deliveries, "deliveries"));
    }

    /** Unit for an emission, before recipients are expanded. */
    public static PipelineMessage of(Message message) {
        return new PipelineMessage(message, null, null, null, false, List.of());
    }

    public PipelineMessage withMessage(Message next) {
        return new PipelineMessage(next, recipient, decision, rendered, cancelEvent, deliveries);
    }

    public PipelineMessage withRecipient(Actor next) {
        return new PipelineMessage(message, next, decision, rendered, cancelEvent, deliveries);
    }

    public PipelineMessage withDecision(RouteDecision next) {
        return new PipelineMessage(message, recipient, next, rendered, cancelEvent, deliveries);
    }

    public PipelineMessage withRendered(RenderedOutput next) {
        return new PipelineMessage(message, recipient, decision, next, cancelEvent, deliveries);
    }

    public PipelineMessage withCancelEvent(boolean next) {
        return new PipelineMessage(message, recipient, decision, rendered, next, deliveries);
    }

    /**
     * @param next rendered units collected for the post phase
     * @return a copy carrying the delivery envelope
     */
    public PipelineMessage withDeliveries(List<DeliveryUnit> next) {
        return new PipelineMessage(message, recipient, decision, rendered, cancelEvent, next);
    }
}
