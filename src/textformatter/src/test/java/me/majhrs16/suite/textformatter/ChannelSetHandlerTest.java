package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.sync.ActionKind;
import me.majhrs16.suite.api.sync.HostActionContext;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncActionSpec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncParamSpec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncValueType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelSetHandlerTest {

    private static final Actor ALICE = new Actor(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Alice",
            Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor BOB = new Actor(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Bob",
            Actor.ActorKind.PLAYER, Language.ES, null);
    private static final Principal PROGRAM = new Principal.Program("a".repeat(64));

    private static final ChannelRegistry CHANNELS = ChannelRegistry.builder()
            .register(Channel.builder("chat").build())
            .register(Channel.builder("staff")
                    .sendPermission("staff.send")
                    .receivePermission("staff.receive")
                    .build())
            .register(Channel.builder("private")
                    .permission("private.use")
                    .build())
            .build();

    private final ChannelSetHandler handler = new ChannelSetHandler();

    @Test
    void declaresTheAgnosticWriteContract() {
        SyncActionSpec spec = handler.action();

        assertEquals(ChannelSetHandler.BACKEND, handler.backend());
        assertEquals("textformatter.channel.set", handler.canonicalName());
        assertEquals("channel.set", spec.id());
        assertEquals(ActionKind.WRITE, spec.kind());
        assertTrue(spec.idempotent());
        assertEquals(1, spec.params().size());
        SyncParamSpec channel = spec.params().get(0);
        assertEquals(ChannelSetHandler.CHANNEL_ARG, channel.name());
        assertEquals(SyncValueType.CHANNEL_REF, channel.type());
        assertTrue(channel.required());
    }

    @Test
    void setsTheChannelAndReturnsTheMutatedMessage() {
        SyncResult result = handler.invoke(call(PROGRAM, Map.of("channel", "private")),
                context(PermissionChecker.ALLOW_ALL));

        assertTrue(result instanceof SyncResult.Ok, result.toString());
        Message message = (Message) ((SyncResult.Ok) result).value();
        assertEquals("private", message.channel());
        assertEquals("hello", message.text(), "the rest of the message must be preserved");
    }

    @Test
    void rejectsOfficialCallers() {
        SyncResult result = handler.invoke(
                call(new Principal.Official("suite.pipeline"), Map.of("channel", "chat")),
                context(PermissionChecker.ALLOW_ALL));

        assertTrue(result instanceof SyncResult.Denied, result.toString());
    }

    @Test
    void rejectsUnknownChannels() {
        SyncResult result = handler.invoke(call(PROGRAM, Map.of("channel", "missing")),
                context(PermissionChecker.ALLOW_ALL));

        assertTrue(result instanceof SyncResult.NotFound, result.toString());
    }

    @Test
    void rejectsBlankOrMissingChannelArguments() {
        assertTrue(handler.invoke(call(PROGRAM, Map.of("channel", " ")),
                context(PermissionChecker.ALLOW_ALL)) instanceof SyncResult.InvalidArgument);
        assertTrue(handler.invoke(call(PROGRAM, Map.of()),
                context(PermissionChecker.ALLOW_ALL)) instanceof SyncResult.InvalidArgument);
    }

    @Test
    void rejectsEmitterWithoutSendPermission() {
        PermissionChecker recipientOnly = (actor, permission) ->
                actor.equals(BOB) && "staff.receive".equals(permission);

        SyncResult result = handler.invoke(call(PROGRAM, Map.of("channel", "staff")),
                context(recipientOnly));

        assertTrue(result instanceof SyncResult.Denied, result.toString());
    }

    @Test
    void rejectsRecipientWithoutReceivePermission() {
        PermissionChecker emitterOnly = (actor, permission) ->
                actor.equals(ALICE) && "staff.send".equals(permission);

        SyncResult result = handler.invoke(call(PROGRAM, Map.of("channel", "staff")),
                context(emitterOnly));

        assertTrue(result instanceof SyncResult.Denied, result.toString());
    }

    @Test
    void allowsChannelsWithoutAnAccessPolicy() {
        SyncResult result = handler.invoke(call(PROGRAM, Map.of("channel", "chat")),
                context((actor, permission) -> false));

        assertTrue(result instanceof SyncResult.Ok, result.toString());
    }

    @Test
    void emptyCatalogIsAnExplicitNotFound() {
        HostActionContext context = new HostActionContext(
                message(), ALICE, BOB, ChannelRegistry.empty(), PermissionChecker.ALLOW_ALL);

        SyncResult result = handler.invoke(call(PROGRAM, Map.of("channel", "chat")), context);

        assertTrue(result instanceof SyncResult.NotFound, result.toString());
    }

    private static SyncCall call(Principal principal, Map<String, Object> args) {
        return new SyncCall(ChannelSetHandler.BACKEND, ChannelSetHandler.ACTION,
                args, principal, "key", "test");
    }

    private static HostActionContext context(PermissionChecker permissions) {
        return new HostActionContext(message(), ALICE, BOB, CHANNELS, permissions);
    }

    private static Message message() {
        return Message.builder().text("hello").sender(ALICE).channel("chat").build();
    }
}
