package me.majhrs16.cht.core.translate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic fake translator for tests. Translations are looked up in a
 * dictionary; unknown words are echoed unchanged.
 */
public final class StubTranslator implements Translator {

    private final Map<String, String> dictionary;
    private final boolean available;

    public StubTranslator() {
        this(true);
    }

    public StubTranslator(boolean available) {
        this.available = available;
        Map<String, String> words = new HashMap<>();
        words.put("hola", "hello");
        words.put("mundo", "world");
        words.put("hello", "hola");
        words.put("world", "mundo");
        this.dictionary = Collections.unmodifiableMap(words);
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public String translate(String text, String from, String to) {
        if (!available) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        for (String token : text.split(" ")) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(dictionary.getOrDefault(token.toLowerCase(), token));
        }
        return result.toString();
    }

    @Override
    public String detect(String text) {
        return "es";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}