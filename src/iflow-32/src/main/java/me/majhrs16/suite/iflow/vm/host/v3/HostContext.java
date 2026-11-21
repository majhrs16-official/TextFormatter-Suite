package me.majhrs16.suite.iflow.vm.host.v3;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import me.majhrs16.suite.api.iflow.TlvCodec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

/**
 * Host bridge view of one message unit.
 *
 * <p>Property reads and writes belong to the unit's {@link MessageJournal}
 * until {@link #commit(HostBridge)}. All other host services delegate to the
 * shared bridge. A unit never shares its overlay or route-decision latch with
 * another unit, even when both run on different cores.</p>
 */
final class HostContext implements HostBridge {

    private final HostBridge delegate;
    private final MessageJournal journal = new MessageJournal();

    HostContext(HostBridge delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    MessageJournal journal() {
        return journal;
    }

    @Override
    public boolean verifyProgram(int pident) {
        return delegate.verifyProgram(pident);
    }

    @Override
    public boolean verifyMessage(int mident) {
        return delegate.verifyMessage(mident);
    }

    @Override
    public Set<String> capabilities(int pident) {
        return delegate.capabilities(pident);
    }

    @Override
    public Optional<TlvCodec.TlvValue> propertyRead(int pident, int mident,
                                                   String scope, String property,
                                                   Map<String, TlvCodec.TlvValue> arguments)
            throws HostServiceException {
        Optional<TlvCodec.TlvValue> overlay = journal.read(scope, property);
        if (overlay.isPresent()) {
            return overlay;
        }
        return delegate.propertyRead(pident, mident, scope, property, arguments);
    }

    @Override
    public void propertyWrite(int pident, int mident, String scope, String property,
                              TlvCodec.TlvValue value) throws HostServiceException {
        delegate.validatePropertyWrite(pident, mident, scope, property, value);
        journal.write(new PropertyWrite(pident, mident, scope, property, value));
    }

    @Override
    public boolean propertyExists(int pident, int mident, String scope, String property)
            throws HostServiceException {
        if (journal.contains(scope, property)) {
            return true;
        }
        return delegate.propertyExists(pident, mident, scope, property);
    }

    @Override
    public boolean permissionCheck(int pident, int mident, String actor,
                                   String channel, String permission)
            throws HostServiceException {
        return delegate.permissionCheck(pident, mident, actor, channel, permission);
    }

    @Override
    public void routeDecision(int pident, int mident, int target,
                              String reason, String error) throws HostServiceException {
        journal.decide(pident, mident, target, reason, error);
    }

    @Override
    public TlvCodec.TlvValue transformApply(int pident, int mident, String kind,
                                            TlvCodec.TlvValue input)
            throws HostServiceException {
        return delegate.transformApply(pident, mident, kind, input);
    }

    @Override
    public String messageId(int mident) throws HostServiceException {
        return delegate.messageId(mident);
    }

    @Override
    public Optional<List<SyncSurface>> syncSurfaces(int pident)
            throws HostServiceException {
        return delegate.syncSurfaces(pident);
    }

    @Override
    public SyncResult syncInvoke(int pident, int mident, String backend,
                                 String action, TlvCodec.TlvValue args,
                                 String idempotencyKey, String reason)
            throws HostServiceException {
        return delegate.syncInvoke(pident, mident, backend, action, args,
                idempotencyKey, reason);
    }

    @Override
    public void validatePropertyWrite(int pident, int mident, String scope,
                                      String property, TlvCodec.TlvValue value)
            throws HostServiceException {
        delegate.validatePropertyWrite(pident, mident, scope, property, value);
    }

    @Override
    public void applyPropertyWrites(List<PropertyWrite> writes)
            throws HostServiceException {
        delegate.applyPropertyWrites(writes);
    }

    void commit(HostBridge target) throws HostServiceException {
        try {
            OptionalInt decision = journal.decision();
            if (decision.isEmpty()) {
                journal.abort();
                return;
            }
            int targetCode = decision.getAsInt();
            if (targetCode == HostAbi.TARGET_DELIVER) {
                target.applyPropertyWrites(journal.writes());
                target.routeDecision(journal.decisionPident(), journal.decisionMident(),
                        targetCode, journal.decisionReason(), journal.decisionError());
                journal.commit();
                return;
            }
            target.routeDecision(journal.decisionPident(), journal.decisionMident(),
                    targetCode, journal.decisionReason(), journal.decisionError());
            journal.abort();
        } catch (HostServiceException | RuntimeException error) {
            journal.abort();
            throw error;
        }
    }

    void abort() {
        journal.abort();
    }
}
