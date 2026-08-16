package me.majhrs16.suite.textformatter.template;

/**
 * Deterministic escaping of user/placeholder text so it is never interpreted
 * as MiniMessage markup.
 */
public final class MiniEscape {

    private MiniEscape() {
    }

    /**
     * @param value raw dynamic text, never null.
     * @return a string safe to embed inside a MiniMessage template.
     */
    public static String escape(String value) {
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