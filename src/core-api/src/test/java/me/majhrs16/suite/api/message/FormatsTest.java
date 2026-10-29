package me.majhrs16.suite.api.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormatsTest {

    @Test
    void formatsOnlyIsNotEmptyAndCountsItsEntries() {
        Formats formats = new Formats(new String[0], new String[] {"<white>%content%</white>"});

        assertThat(formats.isEmpty()).isFalse();
        assertThat(formats.size()).isEqualTo(1);
        assertThat(formats.format(0)).isEqualTo("<white>%content%</white>");
    }

    @Test
    void emptyHasNoTextsAndNoFormats() {
        assertThat(Formats.empty().isEmpty()).isTrue();
        assertThat(Formats.empty().size()).isZero();
    }
}
