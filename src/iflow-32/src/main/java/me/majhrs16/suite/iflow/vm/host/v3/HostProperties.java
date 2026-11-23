package me.majhrs16.suite.iflow.vm.host.v3;

/**
 * Canonical string vocabulary of the v3 {@code PROPERTY_*} services.
 *
 * <p>ADR-037 §7.5 names the TLV arguments {@code scope} and {@code property}
 * but delegates the concrete vocabulary to the host bridge. These constants are
 * that vocabulary for {@code iflow-32}: a scope is a lowercase string and a
 * property is the name used by the policy program. A pair that is not listed
 * here is unknown, so {@code PROPERTY_READ}/{@code PROPERTY_EXISTS} return
 * {@code present = false} and {@code PROPERTY_WRITE} fails explicitly. There is
 * no alias and no v2 numeric id.</p>
 */
public final class HostProperties {

    public static final String SCOPE_MESSAGE = "message";
    public static final String SCOPE_EMITTER = "emitter";
    public static final String SCOPE_RECIPIENT = "recipient";
    public static final String SCOPE_CHANNEL = "channel";

    public static final String MESSAGE_CONTENT = "content";
    public static final String MESSAGE_CHANNEL = "channel";
    public static final String MESSAGE_DIRECTION = "direction";
    public static final String MESSAGE_TYPE = "type";
    public static final String MESSAGE_LANG_SOURCE = "langSource";
    public static final String MESSAGE_LANG_TARGET = "langTarget";
    public static final String MESSAGE_COLOR_MODE = "colorMode";
    public static final String MESSAGE_FORMAT_PAPI = "formatPapi";
    public static final String MESSAGE_TRANSLATE = "translate";

    public static final String ACTOR_NAME = "name";
    public static final String ACTOR_UUID = "uuid";
    public static final String ACTOR_LANGUAGE = "language";

    public static final String CHANNEL_NAME = "name";
    public static final String CHANNEL_RATE = "rate";
    public static final String CHANNEL_SEND_POLICY = "sendPolicy";
    public static final String CHANNEL_RECEIVE_POLICY = "receivePolicy";

    /** Classification of a {@code (scope, property)} pair. */
    public enum Writability {
        WRITABLE,
        READ_ONLY,
        UNKNOWN
    }

    private HostProperties() {
    }

    /**
     * Classifies a pair without resolving any value. Writable pairs are the
     * message fields a policy may mutate through the journal; every other
     * known pair is read-only; anything else is unknown.
     */
    public static Writability writability(String scope, String property) {
        if (SCOPE_MESSAGE.equals(scope)) {
            if (property == null) {
                return Writability.UNKNOWN;
            }
            return switch (property) {
                case MESSAGE_CONTENT,
                        MESSAGE_LANG_SOURCE,
                        MESSAGE_LANG_TARGET,
                        MESSAGE_COLOR_MODE,
                        MESSAGE_FORMAT_PAPI,
                        MESSAGE_TRANSLATE -> Writability.WRITABLE;
                case MESSAGE_CHANNEL, MESSAGE_DIRECTION, MESSAGE_TYPE -> Writability.READ_ONLY;
                default -> Writability.UNKNOWN;
            };
        }
        if (SCOPE_EMITTER.equals(scope) || SCOPE_RECIPIENT.equals(scope)) {
            return switch (String.valueOf(property)) {
                case ACTOR_NAME, ACTOR_UUID, ACTOR_LANGUAGE -> Writability.READ_ONLY;
                default -> Writability.UNKNOWN;
            };
        }
        if (SCOPE_CHANNEL.equals(scope)) {
            return switch (String.valueOf(property)) {
                case CHANNEL_NAME,
                        CHANNEL_RATE,
                        CHANNEL_SEND_POLICY,
                        CHANNEL_RECEIVE_POLICY -> Writability.READ_ONLY;
                default -> Writability.UNKNOWN;
            };
        }
        return Writability.UNKNOWN;
    }
}
