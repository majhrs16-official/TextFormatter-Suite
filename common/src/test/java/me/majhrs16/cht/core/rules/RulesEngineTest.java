package me.majhrs16.cht.core.rules;

import me.majhrs16.cht.core.config.FormatApplier;
import me.majhrs16.cht.core.config.FormatGroups;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.Actor;
import me.majhrs16.cht.core.message.Direction;
import me.majhrs16.cht.core.message.Message;
import me.majhrs16.cht.core.message.MessageType;
import me.majhrs16.cht.core.platform.SilentLogger;
import me.majhrs16.cht.core.scripting.SpelExpressionEvaluator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesEngineTest {

    private static final Actor SENDER = new Actor(
        UUID.randomUUID(), "Majhrs", Actor.ActorKind.PLAYER, Language.ES, null);

    private RulesEngine engine(FormatGroups groups) {
        if (groups == null) {
            ScriptSurface.bindFormatApplier(null);
        } else {
            ScriptSurface.bindFormatApplier(new FormatApplier(groups));
        }
        return new RulesEngine(
            Collections.<RulesEngine.Rule>emptyList(),
            new SpelExpressionEvaluator(), new SilentLogger());
    }

    private FormatGroups groups(String yaml) {
        try {
            return FormatGroups.load(new ByteArrayInputStream(
                yaml.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Message chat(String text) {
        return Message.builder()
            .type(MessageType.CHAT)
            .sender(SENDER)
            .direction(Direction.others())
            .texts(text)
            .langSource(Language.ES)
            .build();
    }

    @Test
    void cancelsWhenActionSaysSo() {
        RulesEngine.Rule rule = new RulesEngine.Rule("cancel",
            Collections.singletonList(MessageType.CHAT),
            Collections.singletonList("text() == 'spam'"),
            Collections.singletonList("cancel()"));
        RulesEngine engine = new RulesEngine(
            Collections.singletonList(rule),
            new SpelExpressionEvaluator(), new SilentLogger());

        List<Message> kept = engine.apply(chat("hola"));
        assertEquals(1, kept.size());

        List<Message> dropped = engine.apply(chat("spam"));
        assertTrue(dropped.isEmpty());
    }

    @Test
    void reformatsThroughSetFormatGroup() {
        FormatGroups groups = groups(
            "remitente_user:\n"
            + "  messages:\n"
            + "    formats:\n"
            + "      - '&b%player_name%: &a%ct_messages%'\n");

        RulesEngine.Rule rule = new RulesEngine.Rule("fmt",
            Collections.singletonList(MessageType.CHAT),
            Collections.<String>emptyList(),
            Collections.singletonList("setFormat('remitente_user')"));
        RulesEngine engine = new RulesEngine(
            Collections.singletonList(rule),
            new SpelExpressionEvaluator(), new SilentLogger());
        ScriptSurface.bindFormatApplier(new FormatApplier(groups));

        List<Message> result = engine.apply(chat("hola"));
        assertEquals(1, result.size());
        Message out = result.get(0);
        assertEquals("remitente_user", out.lastFormatPath());
        assertEquals("&b%player_name%: &a%ct_messages%", out.messages().format(0));
    }

    @Test
    void conditionsRequireAllTrue() {
        RulesEngine.Rule rule = new RulesEngine.Rule("guard",
            Collections.singletonList(MessageType.CHAT),
            Arrays.asList("sender().name() == 'Majhrs'", "size() > 1"),
            Collections.singletonList("cancel()"));
        RulesEngine engine = new RulesEngine(
            Collections.singletonList(rule),
            new SpelExpressionEvaluator(), new SilentLogger());

        // size() == 1 fails the second condition
        List<Message> kept = engine.apply(chat("hola"));
        assertEquals(1, kept.size());
        assertFalse(kept.get(0).isCancelled());
    }

    @Test
    void senderActorIsReachableFromScript() {
        RulesEngine.Rule rule = new RulesEngine.Rule("kind",
            Collections.singletonList(MessageType.CHAT),
            Collections.singletonList("sender().isPlayer()"),
            Collections.<String>emptyList());
        RulesEngine engine = new RulesEngine(
            Collections.singletonList(rule),
            new SpelExpressionEvaluator(), new SilentLogger());

        List<Message> result = engine.apply(chat("hola"));
        assertEquals(1, result.size());
    }

    @Test
    void skippedRuleLeavesMessageUntouched() {
        RulesEngine.Rule rule = new RulesEngine.Rule("skip",
            Collections.singletonList(MessageType.SIGN), // does not match CHAT
            Collections.<String>emptyList(),
            Collections.singletonList("cancel()"));
        RulesEngine engine = new RulesEngine(
            Collections.singletonList(rule),
            new SpelExpressionEvaluator(), new SilentLogger());

        List<Message> result = engine.apply(chat("hola"));
        assertEquals(1, result.size());
        assertFalse(result.get(0).isCancelled());
    }

    @Test
    void surfaceChainsActions() {
        ScriptSurface surface = new ScriptSurface(chat("hola"))
            .setTexts("hello", "world")
            .setLangTarget("en")
            .setFormatPapi(false);

        Message out = surface.message();
        assertEquals(2, out.messages().size());
        assertEquals("hello", out.texts()[0]);
        assertEquals("world", out.texts()[1]);
        assertEquals(Language.EN, out.langTarget());
        assertFalse(out.formatPapi());
        // cancel() marks the message dropped
        assertTrue(surface.cancel().message().isCancelled());
    }
}