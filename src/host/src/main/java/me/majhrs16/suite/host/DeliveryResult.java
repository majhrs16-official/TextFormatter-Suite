package me.majhrs16.suite.host;

import java.util.Objects;

/**
 * Result of delivering one unit to one recipient.
 *
 * @param kind   what happened
 * @param detail reason or short error; never message content
 */
public record DeliveryResult(Kind kind, String detail) {

    public enum Kind {
        /** The recipient received the rendered message. */
        DELIVERED,
        /** Routing discarded or throttled the unit. */
        SILENCED,
        /** The rendered copy went to the console instead of the recipient. */
        REDIRECTED,
        /** Delivery raised; the unit is counted as failed. */
        FAILED
    }

    public DeliveryResult {
        Objects.requireNonNull(kind, "kind");
    }

    public static DeliveryResult delivered() {
        return new DeliveryResult(Kind.DELIVERED, null);
    }

    public static DeliveryResult silenced(String detail) {
        return new DeliveryResult(Kind.SILENCED, detail);
    }

    public static DeliveryResult redirected() {
        return new DeliveryResult(Kind.REDIRECTED, null);
    }

    public static DeliveryResult failed(String detail) {
        return new DeliveryResult(Kind.FAILED, detail);
    }
}
