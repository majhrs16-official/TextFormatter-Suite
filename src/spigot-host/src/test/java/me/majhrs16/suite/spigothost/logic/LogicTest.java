package me.majhrs16.suite.spigothost.logic;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicTest {

    // -- ChannelSelector ---------------------------------------------------

    private static Channel channel(String name, String permission) {
        return Channel.builder(name).permission(permission).build();
    }

    @Test
    void selectorPicksFirstChannelWithAbsentOrGrantedPermission() {
        List<Channel> channels = List.of(
            channel("vip.chat", "suite.vip"),
            channel("chat.global", "cht.global"),
            channel("open", null));

        assertEquals("chat.global",
            ChannelSelector.select(channels, p -> p.equals("cht.global")));
        assertEquals("vip.chat",
            ChannelSelector.select(channels, p -> true));
        assertEquals("open",
            ChannelSelector.select(channels, p -> false));
    }

    @Test
    void selectorFallsBackToChatWhenNothingGranted() {
        List<Channel> channels = List.of(channel("a", "p.a"), channel("b", "p.b"));

        assertEquals("chat", ChannelSelector.select(channels, p -> false));
        assertEquals("chat", ChannelSelector.select(List.of(), p -> true));
    }

    // -- LangSetting -------------------------------------------------------

    @Test
    void langValidationAcceptsAutoOffAndKnownCodes() {
        assertTrue(LangSetting.isValid("auto"));
        assertTrue(LangSetting.isValid("OFF"));
        assertTrue(LangSetting.isValid("zh-CN"));
        assertTrue(LangSetting.isValid(" es "));
        assertFalse(LangSetting.isValid("klingon"));
    }

    @Test
    void langFlipTogglesBetweenOffAndAuto() {
        assertEquals(UserLanguageStore.AUTO, LangSetting.flip(UserLanguageStore.OFF));
        assertEquals(UserLanguageStore.OFF, LangSetting.flip("es"));
        assertEquals(UserLanguageStore.OFF, LangSetting.flip(null));
    }

    @Test
    void effectiveMapsOffToAutoAndKeepsValidCodeOverLocale() {
        assertEquals(Language.AUTO, LangSetting.effective("off", Language.ES));
        assertEquals(Language.ZH_CN, LangSetting.effective("zh-cn", Language.EN));
        assertEquals(Language.ES, LangSetting.effective("auto", Language.ES));
        assertEquals(Language.ES, LangSetting.effective(null, Language.ES));
        assertEquals(Language.ES, LangSetting.effective("klingon", Language.ES));
    }

    @Test
    void displayIsHumanFriendly() {
        assertEquals("off (sin traducción)", LangSetting.display("off"));
        assertEquals("auto (locale del cliente)", LangSetting.display(""));
        assertEquals("es", LangSetting.display("es"));
    }

    // -- EventRules --------------------------------------------------------

    @Test
    void typedEventFiresOnlyWhenItsChannelExists() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(channel("join", null))
            .register(channel("chat", null))
            .build();

        assertTrue(EventRules.typedEventEnabled(registry, "join"));
        assertFalse(EventRules.typedEventEnabled(registry, "quit"));
        assertFalse(EventRules.typedEventEnabled(registry, "death"));
    }

    @Test
    void storedOffDisablesTranslationForThatSender() {
        Map<UUID, String> backing = new ConcurrentHashMap<>();
        UUID steve = UUID.randomUUID();
        UserLanguageStore store = new UserLanguageStore() {
            @Override public Optional<String> languageOf(UUID uuid) {
                return Optional.ofNullable(backing.get(uuid));
            }
            @Override public void save(UUID uuid, String value) { backing.put(uuid, value); }
        };

        assertTrue(EventRules.shouldTranslate(store, steve), "sin ajuste → traduce");
        store.save(steve, UserLanguageStore.OFF);
        assertFalse(EventRules.shouldTranslate(store, steve), "off → literal");
        store.save(steve, "es");
        assertTrue(EventRules.shouldTranslate(store, steve), "código fijo → traduce igualmente");
        assertTrue(EventRules.shouldTranslate(null, steve));
    }
}
