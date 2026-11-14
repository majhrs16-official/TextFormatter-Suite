package me.majhrs16.suite.api.render;

import me.majhrs16.suite.api.pipeline.RenderedOutput;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Contract of the only pipeline type that knows Adventure.
 *
 * <p>The neutral stages receive this value through {@link RenderedOutput}, so
 * the visible text must travel alongside the component and neither half may be
 * missing.</p>
 */
class ComponentRenderedOutputTest {

    @Test
    void isARenderedOutputThatKeepsTheComponentAndItsText() {
        Component component = Component.text("hello").color(NamedTextColor.GREEN);

        ComponentRenderedOutput output = new ComponentRenderedOutput(component, "hello");

        assertThat(output).isInstanceOf(RenderedOutput.class);
        assertThat(output.component()).isSameAs(component);
        assertThat(((RenderedOutput) output).plainText()).isEqualTo("hello");
    }

    @Test
    void rejectsAMissingComponent() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ComponentRenderedOutput(null, "hello"))
                .withMessage("component");
    }

    @Test
    void rejectsMissingPlainText() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ComponentRenderedOutput(Component.empty(), null))
                .withMessage("plainText");
    }
}
