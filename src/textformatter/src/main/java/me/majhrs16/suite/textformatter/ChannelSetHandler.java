package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.HostActionContext;
import me.majhrs16.suite.api.sync.HostActionHandler;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncValueType;

import java.util.List;

/**
 * Host action {@code textformatter.channel.set}.
 *
 * <p>It is the agnostic replacement for the retired {@code set_channel} import
 * of the iFlow ABI v3. It validates that the channel exists and that the
 * emitter may send to it and the recipient may receive from it, then returns
 * the working message with the new channel. The engine detects the channel
 * change and runs exactly one secondary routing pass; a second mutation is a
 * {@code DECISION_CONFLICT}.</p>
 *
 * <p>The action is not part of the least common multiple and
 * {@link Principal.Official} may never invoke it. Third-party programs reach
 * it through {@code sync_invoke("textformatter", "channel.set", ...)} once the
 * operator grants {@code module.textformatter.channel.set}.</p>
 */
public final class ChannelSetHandler implements HostActionHandler {

    /** Namespace of the host action. */
    public static final String BACKEND = "textformatter";

    /** Local action id. */
    public static final String ACTION = "channel.set";

    /** Argument carrying the target channel path. */
    public static final String CHANNEL_ARG = "channel";

    /** Bound for the in-memory validation and message copy. */
    public static final long TIMEOUT_MILLIS = 5_000L;

    private static final SyncActionSpec SPEC = new SyncActionSpec(
            ACTION,
            ActionKind.WRITE,
            List.of(SyncParamSpec.required(CHANNEL_ARG, SyncValueType.CHANNEL_REF)),
            null,
            true,
            TIMEOUT_MILLIS);

    @Override
    public String backend() {
        return BACKEND;
    }

    @Override
    public SyncActionSpec action() {
        return SPEC;
    }

    @Override
    public SyncResult invoke(SyncCall call, HostActionContext context) {
        if (call.principal() instanceof Principal.Official) {
            return new SyncResult.Denied(
                    "Principal.Official may not invoke host actions");
        }

        Object raw = call.args().get(CHANNEL_ARG);
        if (!(raw instanceof String name) || name.isBlank()) {
            return new SyncResult.InvalidArgument(
                    "'" + CHANNEL_ARG + "' must be a non-blank channel reference");
        }

        ChannelRegistry channels = context.channels();
        if (channels.all().isEmpty()) {
            return new SyncResult.NotFound(
                    "channel catalog is empty; textformatter has no channels configured");
        }
        Channel channel = channels.get(name).orElse(null);
        if (channel == null) {
            return new SyncResult.NotFound("channel '" + name + "' does not exist");
        }

        PermissionChecker permissions = context.permissions();
        if (!allowed(context.emitter(), channel.sendPolicy(), permissions)) {
            return new SyncResult.Denied(
                    "emitter lacks '" + channel.sendPolicy()
                            + "' on channel '" + name + "'");
        }
        if (!allowed(context.recipient(), channel.receivePolicy(), permissions)) {
            return new SyncResult.Denied(
                    "recipient lacks '" + channel.receivePolicy()
                            + "' on channel '" + name + "'");
        }

        return new SyncResult.Ok(context.message().withChannel(name));
    }

    private static boolean allowed(Actor actor, String permission,
                                   PermissionChecker permissions) {
        return permission == null || permission.isBlank()
                || permissions.has(actor, permission);
    }
}
