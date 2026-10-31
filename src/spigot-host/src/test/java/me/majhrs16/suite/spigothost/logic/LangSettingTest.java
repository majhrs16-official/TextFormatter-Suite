package me.majhrs16.suite.spigothost.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.junit.jupiter.api.Test;

/** Spigot language-setting semantics used by {@code SpigotActorDirectory}. */
class LangSettingTest {

    @Test
    void acceptsAutoOffAndKnownCodes() {
        assertTrue(LangSetting.isValid("auto"));
        assertTrue(LangSetting.isValid("OFF"));
        assertTrue(LangSetting.isValid("zh-CN"));
        assertTrue(LangSetting.isValid(" es "));
        assertFalse(LangSetting.isValid("klingon"));
    }

    @Test
    void togglesBetweenOffAndAuto() {
        assertEquals(UserLanguageStore.AUTO, LangSetting.flip(UserLanguageStore.OFF));
        assertEquals(UserLanguageStore.OFF, LangSetting.flip("es"));
        assertEquals(UserLanguageStore.OFF, LangSetting.flip(null));
    }

    @Test
    void mapsOffToAutoAndKeepsValidCodeOverLocale() {
        assertEquals(Language.AUTO, LangSetting.effective("off", Language.ES));
        assertEquals(Language.ZH_CN, LangSetting.effective("zh-cn", Language.EN));
        assertEquals(Language.ES, LangSetting.effective("auto", Language.ES));
        assertEquals(Language.ES, LangSetting.effective(null, Language.ES));
        assertEquals(Language.ES, LangSetting.effective("klingon", Language.ES));
    }

    @Test
    void displaysHumanFriendlyValues() {
        assertEquals("off (sin traducción)", LangSetting.display("off"));
        assertEquals("auto (locale del cliente)", LangSetting.display(""));
        assertEquals("es", LangSetting.display("es"));
    }
}
