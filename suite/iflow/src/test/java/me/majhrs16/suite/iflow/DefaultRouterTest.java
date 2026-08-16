package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultRouterTest {

    private static final Actor STEVE = Actor.unknown("Steve");
    private static final Actor ALEX = Actor.unknown("Alex");

    private static ChannelRegistry registry() {
        return ChannelRegistry.builder()
            .register(Channel.builder("chat").build())
            .register(Channel.builder("private").build())
            .build();
    }

    private static Message chat(Actor emitter, String path) {
        return Message.builder()
            .sender(emitter)
            .direction(Direction.others())
            .text("hola")
            .channel(path)
            .build();
    }

    @Test
    void defaultPolicyAcceptsWhenNothingConfigured() {
        DefaultRouter router = new DefaultRouter(registry());

        RouteDecision decision = router.route(chat(STEVE, "chat"), ALEX);

        assertEquals(PolicyTarget.LOG, decision.target());
        assertEquals(ALEX, decision.recipient());
        assertEquals(STEVE, decision.emitter());
    }

    @Test
    void emitterWithoutSendPermissionIsRejected() {
        PermissionChecker permissions = (actor, permission) -> !actor.equals(STEVE)
            || !permission.equals("cht.chat.send");
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat")
                .sendPermission("cht.chat.send")
                .build())
            .build();
        DefaultRouter router = new DefaultRouter(registry, permissions);

        RouteDecision decision = router.route(chat(STEVE, "chat"), ALEX);

        assertEquals(PolicyTarget.REJECT, decision.target());
        assertEquals("cht.chat.send", decision.reason().split(" lacks ")[1].split(" on ")[0]);
    }

    @Test
    void receiverWithoutReceivePermissionIsDropped() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat")
                .receivePermission("cht.chat.receive")
                .build())
            .build();
        PermissionChecker permissions = (actor, permission) -> !actor.equals(ALEX);
        DefaultRouter router = new DefaultRouter(registry, permissions);

        RouteDecision decision = router.route(chat(STEVE, "chat"), ALEX);

        assertEquals(PolicyTarget.DROP, decision.target());
    }

    @Test
    void firstMatchingRuleWinsByPriority() {
        DefaultRouter router = new DefaultRouter(registry());
        router.setRules(Set.of(
            Rule.builder(PolicyTarget.REJECT).priority(10).build(),
            Rule.builder(PolicyTarget.LOG).priority(1).build()));

        assertEquals(PolicyTarget.LOG, router.route(chat(STEVE, "chat"), ALEX).target());
    }

    @Test
    void dropRuleDiscardsForMatchedChannelOnly() {
        DefaultRouter router = new DefaultRouter(registry());
        router.setRules(Set.of(
            Rule.builder(PolicyTarget.DROP)
                .channelPath("private")
                .reason("staff only")
                .build()));

        assertEquals(PolicyTarget.DROP, router.route(chat(STEVE, "private"), ALEX).target());
        assertEquals(PolicyTarget.LOG, router.route(chat(STEVE, "chat"), ALEX).target());
    }

    @Test
    void rateLimitRuleBacksOffAfterBudget() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat").rateLimitPerSecond(1).build())
            .build();
        DefaultRouter router = new DefaultRouter(registry);
        router.setRules(Set.of(
            Rule.builder(PolicyTarget.RATE_LIMIT).reason("throttle").build()));

        assertEquals(PolicyTarget.LOG, router.route(chat(STEVE, "chat"), ALEX).target());
        RouteDecision throttled = router.route(chat(STEVE, "chat"), ALEX);
        assertEquals(PolicyTarget.RATE_LIMIT, throttled.target());
        assertEquals(true, throttled.backoffMillis() > 0);
    }

    @Test
    void emitSeesOwnMessageWithoutExtraPermission() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(Channel.builder("chat")
                .sendPermission("cht.chat.send")
                .build())
            .build();
        PermissionChecker permissions = (actor, permission) -> !actor.equals(STEVE);
        DefaultRouter router = new DefaultRouter(registry, permissions);

        // The emitter keeps their own tail: delivery to the emitter is LOG
        // while delivery exactly to the emitter does not re-check send.
        RouteDecision toSelf = router.route(chat(STEVE, "chat"), STEVE);
        assertEquals(PolicyTarget.LOG, toSelf.target());
    }
}