package me.majhrs16.suite.api.sync;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

import java.util.Objects;

/**
 * Per-invocation state an agnostic host action needs to validate and mutate the
 * message being routed.
 *
 * <p>The context is built by the engine for the current
 * {@code (message × recipient)} unit. It carries the working message, the two
 * actors involved and the neutral services the handler must use: the channel
 * catalog and the platform permission checker. A host action never reaches for
 * ambient authority or for a global catalog.</p>
 *
 * @param message     working message of the current unit
 * @param emitter     actor that originated the event
 * @param recipient   actor that will receive this unit
 * @param channels    channel catalog owned by {@code textformatter}
 * @param permissions platform permission checker
 */
public record HostActionContext(
        Message message,
        Actor emitter,
        Actor recipient,
        ChannelRegistry channels,
        PermissionChecker permissions
) {

    public HostActionContext {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(permissions, "permissions");
    }
}
