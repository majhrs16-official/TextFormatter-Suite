package me.majhrs16.suite.textformatter.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiniEscapeTest {

    @Test
    void escapesTags() {
        assertEquals("\\<tr>\\</tr>", MiniEscape.escape("<tr></tr>"));
    }

    @Test
    void escapesBackslash() {
        assertEquals("\\\\", MiniEscape.escape("\\"));
    }

    @Test
    void leavesPlainTextAlone() {
        assertEquals("hola mundo", MiniEscape.escape("hola mundo"));
    }

    @Test
    void escapesTagsAndBackslashesTogether() {
        // Input (after Java unescaping): <<b>\</b>>  → every < and \ is escaped.
        String escaped = MiniEscape.escape("<<b>\\</b>>");
        assertEquals("\\<\\<b>\\\\\\</b>>", escaped);
    }
}