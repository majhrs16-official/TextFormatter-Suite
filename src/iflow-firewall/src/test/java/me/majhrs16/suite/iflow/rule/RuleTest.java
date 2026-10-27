package me.majhrs16.suite.iflow.rule;

import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.iflow.target.PolicyTarget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleTest {

    @Test
    void matchesEverythingWhenNoMatchers() {
        Rule rule = Rule.builder(PolicyTarget.DROP).build();
        assertTrue(rule.matches("chat", "Steve", "Alex", Direction.others()));
    }

    @Test
    void matchesExactChannel() {
        Rule rule = Rule.builder(PolicyTarget.DROP).channelPath("private").build();
        assertTrue(rule.matches("private", "Steve", "Alex", Direction.others()));
        assertFalse(rule.matches("chat", "Steve", "Alex", Direction.others()));
    }

    @Test
    void matchesWildcardReceiverPattern() {
        Rule rule = Rule.builder(PolicyTarget.REJECT)
            .receiverPattern("staff-*")
            .build();
        assertTrue(rule.matches("chat", "Steve", "staff-juan", Direction.others()));
        assertFalse(rule.matches("chat", "Steve", "player-luis", Direction.others()));
    }

    @Test
    void matchesEmitterAndDirectionKind() {
        Rule rule = Rule.builder(PolicyTarget.LOG)
            .emitterPattern("Steve")
            .direction(Direction.Kind.INITIATOR)
            .build();
        assertTrue(rule.matches("chat", "Steve", "Alex", Direction.initiator()));
        assertFalse(rule.matches("chat", "Steve", "Alex", Direction.others()));
        assertFalse(rule.matches("chat", "Alex", "Alex", Direction.initiator()));
    }
}