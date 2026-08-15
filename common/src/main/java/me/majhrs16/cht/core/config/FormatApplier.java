package me.majhrs16.cht.core.config;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.Formats;
import me.majhrs16.cht.core.message.Message;

import java.util.Objects;

/**
 * Applies a format group from {@link FormatGroups} onto a {@link Message}.
 *
 * <p>Mirrors the original {@code util.apply*Format} contract, but against the
 * new atomic {@code Message} model: a group path loads the {@code messages},
 * {@code toolTips}, {@code sounds} and optional {@code sourceLang}/{@code
 * targetLang} overrides. Empty arrays mean "keep what the message already has",
 * so partial applies compose safely.</p>
 */
public final class FormatApplier {

    private final FormatGroups formats;

    public FormatApplier(FormatGroups formats) {
        this.formats = Objects.requireNonNull(formats, "formats");
    }

    /**
     * Applies the {@code messages} arrays of the group at {@code path}.
     *
     * @param message message to mutate (returns a fresh builder from it).
     * @param path    dotted group path.
     * @return a message builder carrying the applied formats/texts.
     */
    public Message.Builder applyMessages(Message message, String path) {
        Message.Builder builder = message.toBuilder();

        String[] formats = groupFormats(path);
        String[] texts = groupTexts(path);

        // No explicit formats: keep whatever the message already had.
        if (formats.length == 0) {
            formats = message.messages().formats();
        }
        // No texts: keep already bound texts (the literal holds them).
        if (texts.length == 0) {
            texts = message.messages().texts();
        }

        builder.messages(new Formats(texts, formats));

        String[] source = this.formats.sourceLang(path);
        if (source.length > 0) {
            Language lang = Language.of(source[0]).orElse(Language.AUTO);
            builder.langSource(lang);
        }
        String[] target = this.formats.targetLang(path);
        if (target.length > 0) {
            Language lang = Language.of(target[0]).orElse(Language.AUTO);
            builder.langTarget(lang);
        }

        builder.lastFormatPath(path);
        return builder;
    }

    /**
     * Applies the {@code toolTips} arrays of the group at {@code path}.
     *
     * <p>When the group declares no tooltips the current tooltips are kept.</p>
     */
    public Message.Builder applyToolTips(Message message, String path) {
        Message.Builder builder = message.toBuilder();

        String[] formats = this.formats.toolTipFormats(path);
        String[] texts = this.formats.toolTipTexts(path);

        if (formats.length == 0) {
            formats = message.toolTips().formats();
        }
        if (texts.length == 0) {
            texts = message.toolTips().texts();
        }

        builder.toolTips(new Formats(texts, formats));
        return builder;
    }

    /**
     * Applies the {@code sounds} of the group at {@code path}.
     *
     * <p>When the group declares no sounds the current sounds are kept.</p>
     */
    public Message.Builder applySounds(Message message, String path) {
        Message.Builder builder = message.toBuilder();
        me.majhrs16.cht.core.message.SoundSpec[] sounds = formats.sounds(path);
        if (sounds.length > 0) {
            String[] raw = new String[sounds.length];
            for (int i = 0; i < sounds.length; i++) {
                raw[i] = sounds[i].name() + ";"
                    + sounds[i].volume() + ";" + sounds[i].pitch();
            }
            builder.sounds(raw);
        }
        return builder;
    }

    /**
     * Applies every aspect of a group (messages, tooltips, sounds) in one shot.
     */
    public Message.Builder apply(Message message, String path) {
        Message.Builder builder = applyMessages(message, path);
        builder = applyToolTips(builder.build(), path);
        return applySounds(builder.build(), path);
    }

    private String[] groupFormats(String path) {
        // Group can be a bare literal list too.
        return formats.messageFormats(path);
    }

    private String[] groupTexts(String path) {
        return formats.messageTexts(path);
    }
}