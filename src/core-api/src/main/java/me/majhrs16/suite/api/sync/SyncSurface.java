package me.majhrs16.suite.api.sync;

import me.majhrs16.suite.api.SemVer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Actions published by one sync backend.
 *
 * <p>{@code backend} is the namespace used in {@code <backend>.<action>}; it is
 * not the sink display name. Every published surface must contain the official
 * least common multiple ({@code backend.info} and {@code message.send}).</p>
 *
 * @param backend         namespace, e.g. {@code discord} or {@code telegram}
 * @param contractVersion version of this surface contract
 * @param actions         published actions, without duplicate ids
 */
public record SyncSurface(
        String backend,
        SemVer contractVersion,
        Set<SyncActionSpec> actions
) {

    /** Local id of the mandatory metadata action. */
    public static final String BACKEND_INFO = "backend.info";

    /** Local id of the mandatory message delivery action. */
    public static final String MESSAGE_SEND = "message.send";

    /** Version of the surface contract implemented by the suite. */
    public static final SemVer CURRENT_CONTRACT_VERSION = SemVer.of(1, 0, 0);

    public SyncSurface {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        if (!backend.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "backend must be a single namespace segment without dots or spaces: '"
                            + backend + "'");
        }
        Objects.requireNonNull(contractVersion, "contractVersion");
        actions = Set.copyOf(Objects.requireNonNull(actions, "actions"));
        long distinct = actions.stream().map(SyncActionSpec::id).distinct().count();
        if (distinct != actions.size()) {
            throw new IllegalArgumentException(
                    "surface '" + backend + "' declares a duplicate action id");
        }
    }

    /** @return the spec for {@code action}, or empty when not published */
    public Optional<SyncActionSpec> action(String action) {
        return actions.stream().filter(spec -> spec.id().equals(action)).findFirst();
    }

    /** @return whether this surface publishes every mandatory LCM action */
    public boolean hasLeastCommonMultiple() {
        return action(BACKEND_INFO).isPresent() && action(MESSAGE_SEND).isPresent();
    }

    /**
     * Builds the minimum surface every backend must support: metadata plus
     * message delivery.
     */
    public static SyncSurface lcm(String backend, SemVer contractVersion) {
        List<SyncParamSpec> sendParams = List.of(
                SyncParamSpec.required("message", SyncValueType.MESSAGE_REF),
                SyncParamSpec.optional("destination", SyncValueType.CHANNEL_REF));

        return new SyncSurface(backend, contractVersion, Set.of(
                new SyncActionSpec(
                        BACKEND_INFO,
                        ActionKind.READ,
                        List.of(),
                        SyncParamSpec.optional("info", SyncValueType.OBJECT),
                        true,
                        5_000),
                new SyncActionSpec(
                        MESSAGE_SEND,
                        ActionKind.WRITE,
                        sendParams,
                        null,
                        true,
                        15_000)));
    }
}
