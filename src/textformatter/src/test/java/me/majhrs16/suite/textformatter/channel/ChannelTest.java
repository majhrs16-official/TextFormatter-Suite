package me.majhrs16.suite.textformatter.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelTest {

    @Test
    void sendPolicyPrefersExplicitOverride() {
        Channel channel = Channel.builder("chat")
            .permission("cht.chat")
            .sendPermission("cht.chat.send")
            .receivePermission("cht.chat.receive")
            .build();

        assertEquals("cht.chat.send", channel.sendPolicy());
        assertEquals("cht.chat.receive", channel.receivePolicy());
    }

    @Test
    void policiesFallBackToBasePermission() {
        Channel channel = Channel.builder("chat")
            .permission("cht.chat")
            .build();

        assertEquals("cht.chat", channel.sendPolicy());
        assertEquals("cht.chat", channel.receivePolicy());
    }

    @Test
    void defaultPolicyIsNullMeaningAccept() {
        Channel channel = Channel.builder("chat").build();

        assertNull(channel.sendPolicy());
        assertNull(channel.receivePolicy());
    }

    @Test
    void readsBackConfiguration() {
        Channel channel = Channel.builder("chat.global")
            .rateLimitPerSecond(5)
            .showSender(false)
            .build();

        assertEquals("chat.global", channel.name());
        assertEquals(5, channel.rateLimitPerSecond());
        assertEquals(false, channel.showSender());
    }
}