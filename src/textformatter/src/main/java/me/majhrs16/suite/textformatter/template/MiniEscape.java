package me.majhrs16.suite.textformatter.template;

/**
 * Deterministic escaping of user/placeholder text so it is never interpreted
 * as MiniMessage markup.
 * <p>
 * Escapes characters that have special meaning in MiniMessage:
 * </p>
 * <ul>
 *   <li>{@code <} {@code >} — tag delimiters</li>
 *   <li>{@code \} — escape character</li>
 *   <li>{@code {}} — component/placeholder delimiters</li>
 *   <li>{@code []} — hover/click event delimiters</li>
 *   <li>{@code ()} — grouping</li>
 *   <li>{@code #} — hex color prefix</li>
 *   <li>{@code @} — mention/reference prefix</li>
 * </ul>
 */
public final class MiniEscape {

    private MiniEscape() {
    }

    /**
     * @param value raw dynamic text, never null.
     * @return a string safe to embed inside a MiniMessage template.
     */
    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '<':
                case '>':
                case '\\':
                case '{':
                case '}':
                case '[':
                case ']':
                case '(':
                case ')':
                case '#':
                case '@':
                    escaped.append('\\').append(c);
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }
}