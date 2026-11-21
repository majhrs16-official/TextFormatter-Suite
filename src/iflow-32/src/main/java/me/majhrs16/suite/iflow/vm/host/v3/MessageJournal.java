package me.majhrs16.suite.iflow.vm.host.v3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import me.majhrs16.suite.api.iflow.TlvCodec;

/**
 * Open write set and route decision of one message unit (ADR-037 §6.1.9).
 *
 * <p>Every message has its own journal. Reads see the provisional overlay
 * (read-your-writes); another unit cannot see it before the host commits. The
 * journal itself does not mutate the host bridge: {@link HostContext} applies
 * the write set on commit and discards it on abort.</p>
 */
public final class MessageJournal {

    public enum State {
        OPEN,
        COMMITTED,
        ABORTED
    }

    private record PropertyKey(String scope, String property) {
    }

    private final Map<PropertyKey, PropertyWrite> writes = new LinkedHashMap<>();
    private int decisionPident;
    private int decisionMident;
    private Integer decisionTarget;
    private String decisionReason;
    private String decisionError;
    private State state = State.OPEN;

    public synchronized void write(PropertyWrite write) {
        requireOpen();
        Objects.requireNonNull(write, "write");
        writes.put(new PropertyKey(write.scope(), write.property()), write);
    }

    /**
     * Reads only the provisional overlay. A miss means the caller must read
     * the host bridge.
     */
    public synchronized Optional<TlvCodec.TlvValue> read(String scope, String property) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(property, "property");
        PropertyWrite write = writes.get(new PropertyKey(scope, property));
        return write == null ? Optional.empty() : Optional.of(write.value());
    }

    public synchronized boolean contains(String scope, String property) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(property, "property");
        return writes.containsKey(new PropertyKey(scope, property));
    }

    public synchronized void decide(int pident, int mident, int target,
                                    String reason, String error)
            throws HostServiceException {
        requireOpen();
        if (decisionTarget != null) {
            throw HostServiceException.conflict("ROUTE_DECISION already recorded");
        }
        decisionPident = pident;
        decisionMident = mident;
        decisionTarget = target;
        decisionReason = reason;
        decisionError = error;
    }

    public synchronized OptionalInt decision() {
        return decisionTarget == null
                ? OptionalInt.empty()
                : OptionalInt.of(decisionTarget);
    }

    public synchronized int decisionPident() {
        requireDecision();
        return decisionPident;
    }

    public synchronized int decisionMident() {
        requireDecision();
        return decisionMident;
    }

    public synchronized String decisionReason() {
        requireDecision();
        return decisionReason;
    }

    public synchronized String decisionError() {
        requireDecision();
        return decisionError;
    }

    public synchronized List<PropertyWrite> writes() {
        return List.copyOf(writes.values());
    }

    public synchronized State state() {
        return state;
    }

    public synchronized void commit() {
        requireOpen();
        state = State.COMMITTED;
    }

    public synchronized void abort() {
        requireOpen();
        writes.clear();
        decisionTarget = null;
        decisionReason = null;
        decisionError = null;
        state = State.ABORTED;
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Journal already " + state.name().toLowerCase());
        }
    }

    private void requireDecision() {
        if (decisionTarget == null) {
            throw new IllegalStateException("Journal has no route decision");
        }
    }
}
