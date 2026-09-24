package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.template.Template;
import me.majhrs16.suite.textformatter.template.TemplateContext;
import me.majhrs16.suite.textformatter.template.TemplateRenderer;

import net.kyori.adventure.text.Component;

import java.util.Objects;

/**
 * Default {@link TextFormatter} implementation.
 *
 * <p>Channel selection is path-based with ancestor fallback (see
 * {@link ChannelRegistry#resolve(String)}). Formats from the channel and the
 * message compose the same way the v4 applier did: when the channel tail has
 * no explicit formats, the message's own formats win; empty texts keep the
 * message's already-bound texts.</p>
 */
public final class DefaultTextFormatter implements TextFormatter {

    private final ChannelRegistry channels;
    private final TemplateRenderer renderer;

    public DefaultTextFormatter(ChannelRegistry channels, TemplateRenderer renderer) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public ChannelRegistry channels() {
        return channels;
    }

    @Override
    public TemplateRenderer renderer() {
        return renderer;
    }

    @Override
    public Component format(Message message, TemplateContext context) {
        Channel tail = tail(message, message.channel());
        Formats merged = merge(tail.messages(), message.messages());
        Template template = Template.of(merged.format(0)
            .replace(Channel.PLACEHOLDER, "%content%"));
        return renderer.render(template, context);
    }

    @Override
    public Component formatTooltip(Message message, TemplateContext context) {
        Channel tail = tail(message, message.channel());
        Formats merged = merge(tail.tooltips(), message.toolTips());
        if (merged.isEmpty()) {
            return Component.empty();
        }
        Template template = Template.of(merged.format(0)
            .replace(Channel.PLACEHOLDER, "%content%"));
        return renderer.render(template, context);
    }

    @Override
    public Component render(Template template, TemplateContext context) {
        return renderer.render(template, context);
    }

    @Override
    public Channel tail(Message message, String channelPath) {
        return channels.resolve(channelPath);
    }

    private static Formats merge(Formats tail, Formats message) {
        String[] formats = tail.formats();
        String[] texts = tail.texts();
        if (formats.length == 0) {
            formats = message.formats();
        }
        if (texts.length == 0) {
            texts = message.texts();
        }
        return new Formats(texts, formats);
    }
}