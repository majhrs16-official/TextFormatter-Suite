package me.majhrs16.suite.api.pipeline;

import me.majhrs16.suite.api.message.Message;

import java.util.List;
import java.util.Objects;

/**
 * Delivery payload handed to the {@code sync} multiplexer.
 *
 * <p>The envelope keeps the original {@link Message} together with the
 * recipient-specific units the pipeline already rendered. It is the payload of
 * the {@code message.deliver} action: external sinks may ignore the units and
 * deliver the plain message, while local sinks deliver each unit through the
 * {@code LocalDelivery} port.</p>
 *
 * @param message the message that produced the units
 * @param units   rendered units in recipient order; may be empty
 */
public record DeliveryEnvelope(
        Message message,
        List<DeliveryUnit> units
) {

    public DeliveryEnvelope {
        Objects.requireNonNull(message, "message");
        units = List.copyOf(Objects.requireNonNull(units, "units"));
    }

    /** @return an envelope with no units for {@code message} */
    public static DeliveryEnvelope empty(Message message) {
        return new DeliveryEnvelope(message, List.of());
    }

    /** @return the same envelope with {@code extra} units appended */
    public DeliveryEnvelope withUnits(List<DeliveryUnit> extra) {
        Objects.requireNonNull(extra, "extra");
        if (extra.isEmpty()) {
            return this;
        }
        List<DeliveryUnit> combined = new java.util.ArrayList<>(units.size() + extra.size());
        combined.addAll(units);
        combined.addAll(extra);
        return new DeliveryEnvelope(message, combined);
    }
}
