package me.majhrs16.suite.textformatter.template;

/**
 * Deterministic escaping of user/placeholder text so it is never interpreted
 * as MiniMessage markup.
 * <p>
 * Escapes characters that have special meaning in MiniMessage:
 * <ul>
 *   <li>`<` `>` — tag delimiters</li>
 *   <li>`\` — escape character</li>
 *   <li>`{` `}` — placeholder/group delimiters</li>
 *   <li>`[` `]` — array/list delimiters</li>
 *   <li>`(` `)` — function/argument delimiters</li>
 *   <li>`#` — color/hex prefix</li>
 *   <li>`@` — mention/reference prefix</li>
 * </ul>
 * </p>
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