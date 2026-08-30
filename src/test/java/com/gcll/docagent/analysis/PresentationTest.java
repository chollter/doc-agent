package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表达质量档位映射测试——纯代码，P7 评测断言锚点。
 */
class PresentationTest {

    @Test
    void shouldMapScoreToBands() {
        assertThat(Presentation.bandOf(90)).isEqualTo(Presentation.Band.A);
        assertThat(Presentation.bandOf(85)).isEqualTo(Presentation.Band.A);
        assertThat(Presentation.bandOf(74)).isEqualTo(Presentation.Band.B);
        assertThat(Presentation.bandOf(60)).isEqualTo(Presentation.Band.C);
        assertThat(Presentation.bandOf(30)).isEqualTo(Presentation.Band.D);
    }

    @Test
    void shouldClampAndDefault() {
        assertThat(Presentation.of(150, null).score()).isEqualTo(100);
        assertThat(Presentation.of(-5, null).score()).isZero();
        assertThat(Presentation.of(null, null).band()).isEqualTo(Presentation.Band.D);
        assertThat(Presentation.of(80, null).issues()).isEmpty();
    }
}
