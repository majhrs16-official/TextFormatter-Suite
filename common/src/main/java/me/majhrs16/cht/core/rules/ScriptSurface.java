package me.majhrs16.cht.core.rules;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.Actor;
import me.majhrs16.cht.core.message.ColorMode;
import me.majhrs16.cht.core.message.Direction;
import me.majhrs16.cht.core.message.Message;
import me.majhrs16.cht.core.message.MessageType;

/**
 * The mutable scripting surface exposed to SpEL rules and commands.
 *
 * <p>This is the native replacement of the old CoreTranslator's
 * {@code #from} builder: a live, atomic handle over a {@code Message} that
 * every rule action mutates in place and which leaves a value for the next
 * action by returning {@code this}. Operations are deliberately granular
 * (getters plus per-field setters) so a script can touch exactly one field --
 * the "assembler" philosophy where everything is reachable -- while a few
 * helpers ({@code setFormat}, {@code clone}) cover the common high-level
 * moves.</p>
 */
public final class ScriptSurface {

    private Message message;

    public ScriptSurface(Message message) {
        this.message = message;
    }

    // -- reads (atomic) ------------------------------------------------------

    public Actor sender() {
        return message.sender();
    }

    public MessageType type() {
        return message.type();
    }

    public Direction direction() {
        return message.direction();
    }

    public String text() {
        return message.text();
    }

    public String[] texts() {
        return message.texts();
    }

    public String text(int index) {
        return message.messages().isEmpty() ? "" : message.messages().text(index);
    }

    public int size() {
        return message.messages().size();
    }

    public Language langSource() {
        return message.langSource();
    }

    public Language langTarget() {
        return message.langTarget();
    }

    public ColorMode colorMode() {
        return message.colorMode();
    }

    public boolean isCancelled() {
        return message.isCancelled();
    }

    public boolean isShown() {
        return message.isShown();
    }

    public boolean formatPapi() {
        return message.formatPapi();
    }

    public String lastFormatPath() {
        return message.lastFormatPath();
    }

    public String id() {
        return message.id().toString();
    }

    // -- mutations (atomic, chainable) ---------------------------------------

    public ScriptSurface setType(MessageType type) {
        message = message.toBuilder().type(type).build();
        return this;
    }

    public ScriptSurface setDirection(Direction direction) {
        message = message.toBuilder().direction(direction).build();
        return this;
    }

    public ScriptSurface setText(String text) {
        return setTexts(text);
    }

    public ScriptSurface setTexts(String... texts) {
        message = message.toBuilder().texts(texts).build();
        return this;
    }

    public ScriptSurface setText(int index, String text) {
        message = message.toBuilder()
            .messages(message.messages().toBuilder().text(index, text).build())
            .build();
        return this;
    }

    public ScriptSurface setLangSource(String lang) {
        return setLangSource(Language.of(lang).orElse(Language.AUTO));
    }

    public ScriptSurface setLangSource(Language lang) {
        message = message.toBuilder().langSource(lang).build();
        return this;
    }

    public ScriptSurface setLangTarget(String lang) {
        return setLangTarget(Language.of(lang).orElse(Language.AUTO));
    }

    public ScriptSurface setLangTarget(Language lang) {
        message = message.toBuilder().langTarget(lang).build();
        return this;
    }

    public ScriptSurface setColorMode(ColorMode colorMode) {
        message = message.toBuilder().colorMode(colorMode).build();
        return this;
    }

    public ScriptSurface setFormatPapi(boolean formatPapi) {
        message = message.toBuilder().formatPapi(formatPapi).build();
        return this;
    }

    public ScriptSurface show() {
        message = message.toBuilder().show(true).build();
        return this;
    }

    public ScriptSurface hide() {
        message = message.toBuilder().show(false).build();
        return this;
    }

    /** Marks the message as cancelled (dropped before delivery). */
    public ScriptSurface cancel() {
        message = message.toBuilder().cancelled(true).build();
        return this;
    }

    /** Marks the message translated (skip translation). */
    public ScriptSurface skipTranslate() {
        message = message.toBuilder().translate(false).build();
        return this;
    }

    // -- helpers (high level) ------------------------------------------------

    /**
     * Applies a format group by path using the engine's {@code FormatApplier}.
     *
     * @param path dotted group path, e.g. {@code remitente_user}.
     * @return this surface for chaining.
     */
    public ScriptSurface setFormat(String path) {
        return setFormat(path, message);
    }

    private ScriptSurface setFormat(String path, Message source) {
        me.majhrs16.cht.core.config.FormatApplier applier = Formats.get();
        if (applier == null) {
            return this;
        }
        message = applier.apply(source, path).build();
        return this;
    }

    /** Returns a detached clone of the current message (safe to mutate). */
    public ScriptSurface clone() {
        return new ScriptSurface(message.toBuilder().build());
    }

    /** Converts the message to its JSON form (the old {@code Message.toJson}). */
    public String toJson() {
        return Json.write(message);
    }

    /** @return the message as currently built. */
    public Message message() {
        return message;
    }

    @Override
    public String toString() {
        return "ScriptSurface[" + message + "]";
    }

    /**
     * Bridge to the format engine, backfilled once the app wires it.
     * Rules read format groups by path through here.
     */
    private static final class Formats {
        private static me.majhrs16.cht.core.config.FormatApplier applier;

        private static me.majhrs16.cht.core.config.FormatApplier get() {
            return applier;
        }
    }

    /** JSON serialization bridge, delegated so the core stays dependency-free here. */
    private static final class Json {
        private static me.majhrs16.cht.core.message.JsonCodec codec;

        private static String write(Message message) {
            return codec != null ? codec.write(message) : "{}";
        }
    }

    /** Wire points, injected by {@code ChatTranslatorApp}. */
    public static void bindFormatApplier(me.majhrs16.cht.core.config.FormatApplier applier) {
        Formats.applier = applier;
    }

    public static void bindJsonCodec(me.majhrs16.cht.core.message.JsonCodec codec) {
        Json.codec = codec;
    }
}