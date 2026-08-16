package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.template.Template;
import me.majhrs16.suite.textformatter.template.TemplateContext;
import me.majhrs16.suite.textformatter.template.TemplateRenderer;

import net.kyori.adventure.text.Component;

/**
 * The rendering contract of the TextFormatter module.
 *
 * <p>Any consumer (iFlow, a platform adapter, a sync bridge) obtains an
 * instance through {@link java.util.ServiceLoader} and uses it to turn an
 * atomic {@link Message} into Adventure components: the {@link ChannelRegistry}
 * selects the tail (formats/tooltips/sounds/languages/permissions) and the
 * {@link TemplateRenderer} does the actual MiniMessage rendering.</p>
 */
public interface TextFormatter {

    /** @return the immutable channel catalog bound to this instance. */
    ChannelRegistry channels();

    /** @return the template renderer bound to this instance. */
    TemplateRenderer renderer();

    /**
     * Applies a channel tail onto a message and renders its {@code messages}
     * formats for the given recipient context.
     */
    Component format(Message message, TemplateContext context);

    /**
     * Applies a channel tail onto a message and renders its {@code tooltips}
     * formats for the given recipient context.
     */
    Component formatTooltip(Message message, TemplateContext context);

    /**
     * Renders a raw authored template without a channel (e.g. banner, rule
     * output) for the given recipient context.
     */
    Component render(Template template, TemplateContext context);

    /** The tail formats of a channel, applied over the message's own formats. */
    Channel tail(Message message, String channelPath);
}