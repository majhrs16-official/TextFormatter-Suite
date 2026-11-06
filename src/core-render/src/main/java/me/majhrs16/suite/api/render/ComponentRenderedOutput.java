package me.majhrs16.suite.api.render;

import me.majhrs16.suite.api.pipeline.RenderedOutput;

import net.kyori.adventure.text.Component;

import java.util.Objects;

/**
 * Adventure-backed {@link RenderedOutput}.
 *
 * <p>This is the only pipeline type that knows {@code Component}. The plain
 * text is carried alongside the component so neutral stages (for example
 * {@code sync}) never have to depend on Adventure or serialize it
 * themselves.</p>
 *
 * @param component the rendered component
 * @param plainText the visible text of {@code component}
 */
public record ComponentRenderedOutput(Component component, String plainText)
        implements RenderedOutput {

    public ComponentRenderedOutput {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(plainText, "plainText");
    }
}
