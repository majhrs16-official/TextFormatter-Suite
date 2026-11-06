package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

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
 * @param message    the message content and routing direction
 * @param recipient  the recipient of this unit, or {@code null}
 * @param decision   the routing decision, or {@code null}
 * @param rendered   the rendered output, or {@code null}
 * @param cancelEvent whether the originating platform event must be cancelled
 */
public record PipelineMessage(
        Message message,
        Actor recipient,
        RouteDecision decision,
        RenderedOutput rendered,
        boolean cancelEvent
) {

    public PipelineMessage {
        Objects.requireNonNull(message, "message");
    }

    /** Unit for an emission, before recipients are expanded. */
    public static PipelineMessage of(Message message) {
        return new PipelineMessage(message, null, null, null, false);
    }

    public PipelineMessage withMessage(Message next) {
        return new PipelineMessage(next, recipient, decision, rendered, cancelEvent);
    }

    public PipelineMessage withRecipient(Actor next) {
        return new PipelineMessage(message, next, decision, rendered, cancelEvent);
    }

    public PipelineMessage withDecision(RouteDecision next) {
        return new PipelineMessage(message, recipient, next, rendered, cancelEvent);
    }

    public PipelineMessage withRendered(RenderedOutput next) {
        return new PipelineMessage(message, recipient, decision, next, cancelEvent);
    }

    public PipelineMessage withCancelEvent(boolean next) {
        return new PipelineMessage(message, recipient, decision, rendered, next);
    }
}
