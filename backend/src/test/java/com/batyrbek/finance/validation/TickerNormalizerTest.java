package com.batyrbek.finance.validation;

import com.batyrbek.finance.exception.InvalidTickerException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TickerNormalizerTest {
    private final TickerNormalizer normalizer = new TickerNormalizer();

    @Test
    void trimsAndUppercasesTicker() {
        assertThat(normalizer.normalize("  brk.b ")).isEqualTo("BRK.B");
    }

    @Test
    void rejectsUnsafeTicker() {
        assertThatThrownBy(() -> normalizer.normalize("NVDA/../../secret"))
                .isInstanceOf(InvalidTickerException.class)
                .hasMessageContaining("1-10");
    }
}
