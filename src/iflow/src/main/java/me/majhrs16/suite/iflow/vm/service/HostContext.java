package me.majhrs16.suite.iflow.vm.service;

import java.util.Optional;

/**
 * Formal port through which the VM reaches host-owned state during an execution
 * (IFLOW-AUD-016).
 * <p>
 * The service implementations in {@link HostServiceRegistry} are pure dispatchers:
 * they decode the HOST ABI and copy bytes in/out of VM memory, but every value
 * that actually belongs to the host (a message property, a channel name, a
 * persistent state entry) is read or written through this interface. The host
 * supplies one {@code HostContext} per execution (the engine passes the current
 * message/actor/channel view), which removes the loose, un-wired lambdas that
 * the registry used to carry.
 * </p>
 * <p>
 * Every method has a safe default, so a host only overrides what it supports.
 * {@link #NONE} is the null-object used when no context is installed.
 * </p>
 * <p>
 * Absent values are modelled with {@link Optional} (D29) instead of a bare
 * {@code null}: the API makes "not present" explicit and impossible to ignore.
 * </p>
 */
public interface HostContext {

    /** Null-object port: every read is absent and every write is dropped. */
    HostContext NONE = new HostContext() {};

    /**
     * Reads a host property.
     *
     * @param scope  one of the {@code SCOPE_*} constants
     * @param propId one of the {@code PROP_*} constants
     * @return the value, or {@link Optional#empty()} if the property does not exist
     */
    default Optional<String> readProperty(int scope, int propId) {
        return Optional.empty();
    }

    /**
     * Records a property write requested by the program. Message mutation is
     * deferred: the host decides how to apply it (the reference host journals the
     * matching edit, which the engine validates and commits after execution).
     *
     * @return {@code true} if the host accepted the write; {@code false} if the
     *         {@code (scope, propId)} pair is not writable
     */
    default boolean writeProperty(int scope, int propId, String value) {
        return false;
    }

    /**
     * Resolves a channel name to its canonical id.
     *
     * @return the canonical channel name, or {@link Optional#empty()} if it is unknown
     */
    default Optional<String> resolveChannel(String name) {
        return Optional.empty();
    }

    /**
     * Reads a policy of a <em>named</em> channel (the {@code PROP_CHAN_*} ids).
     * Unlike {@link #readProperty(int, int)} with {@link HostServiceRegistry#SCOPE_CHANNEL}
     * — which reads the current message's channel — this addresses any channel by
     * name. Backs the {@code CHANNEL_POLICY} service.
     *
     * @return the value, or {@link Optional#empty()} if the channel is unknown or the
     *         property is absent
     */
    default Optional<String> readChannelProperty(String channelName, int propId) {
        return Optional.empty();
    }

    /**
     * Reads a persistent host state entry.
     *
     * @return the stored value, or {@link Optional#empty()} if the key is absent
     */
    default Optional<Object> readState(int key) {
        return Optional.empty();
    }

    /** Writes a persistent host state entry. */
    default void writeState(int key, Object value) {
    }
}
