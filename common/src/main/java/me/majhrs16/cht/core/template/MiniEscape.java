package me.majhrs16.cht.core.template;

/**
 * Deterministic escaping of user/placeholder text so it is never interpreted
 * as MiniMessage markup.
 *
 * <p>MiniMessage treats a backslash before a tag character as a literal
 * escape and consumes the backslash. Only two characters are actually
 * dangerous inside text: {@code <} (opens a tag) and {@code \} (the escape
 * character itself). Escaping {@code >} is unnecessary (a lone {@code >} is
 * plain text) and would leak the backslash, and {@code :} is only special
 * inside tag arguments, not in text.</p>
 */
final class MiniEscape {

    private MiniEscape() {
    }

    /**
     * @param value raw dynamic text, never null.
     * @return a string safe to embed inside a MiniMessage template.
     */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '<':
                    escaped.append("\\<");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }
}