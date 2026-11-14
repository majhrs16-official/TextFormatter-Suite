package me.majhrs16.suite.api.sync;

/**
 * Executes one agnostic host action.
 *
 * <p>Host actions do not belong to a sync sink and therefore do not publish a
 * {@link SyncSurface}. They are registered as services and the bus resolves
 * them internally by their canonical {@code <backend>.<action>} name. The
 * handler validates the action arguments against its {@link SyncActionSpec},
 * checks the permissions carried by the {@link HostActionContext} and returns
 * the explicit {@link SyncResult}; a denied caller has no effect.</p>
 *
 * <p>Authorization is the bus's responsibility: it denies
 * {@link Principal.Official} for every host action and
 * {@link Principal.Program} without an operator grant that covers the
 * {@code module.<backend>.<action>} capability. A handler only has to reject
 * an official caller that reaches it directly.</p>
 */
public interface HostActionHandler {

    /** @return namespace of the host action, e.g. {@code textformatter} */
    String backend();

    /** @return declarative contract of the action, never {@code null} */
    SyncActionSpec action();

    /**
     * Executes the action for one routing unit.
     *
     * @param call    invocation with neutral arguments and principal
     * @param context current message and neutral services
     * @return the explicit outcome, never {@code null}
     */
    SyncResult invoke(SyncCall call, HostActionContext context);

    /** @return the canonical {@code <backend>.<action>} name */
    default String canonicalName() {
        return backend() + "." + action().id();
    }

    /** @return whether this handler owns {@code call}'s backend and action */
    default boolean handles(SyncCall call) {
        return call != null
                && backend().equals(call.backend())
                && action().id().equals(call.action());
    }
}
