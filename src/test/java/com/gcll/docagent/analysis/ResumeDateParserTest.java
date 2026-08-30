package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一日期解析的单元测试——重点覆盖 P11 三处重复实现均不支持的中文日期格式，
 * 以及开放端点（至今）与噪声容忍。
 */
class ResumeDateParserTest {

    @Test
    void shouldParseAllSupportedFormats() {
        assertThat(ResumeDateParser.parseRange("2020.03-2021.05"))
                .containsExactly(LocalDate.of(2020, 3, 1), LocalDate.of(2021, 5, 1));
        assertThat(ResumeDateParser.parseRange("2020/3-2021/5"))
                .containsExactly(LocalDate.of(2020, 3, 1), LocalDate.of(2021, 5, 1));
        assertThat(ResumeDateParser.parseRange("2020-03 - 2021-05"))
                .containsExactly(LocalDate.of(2020, 3, 1), LocalDate.of(2021, 5, 1));
        // 中文格式——P11 的 \\d{4}[./-]\\d{1,2} 无法识别，时间线检查对这类简历静默失效
        assertThat(ResumeDateParser.parseRange("2020年3月 - 2021年5月"))
                .containsExactly(LocalDate.of(2020, 3, 1), LocalDate.of(2021, 5, 1));
    }

    @Test
    void shouldTreatOpenEndedSuffixAsNow() {
        LocalDate[] range = ResumeDateParser.parseRange("2022.03 至今");
        assertThat(range).isNotNull();
        assertThat(range[0]).isEqualTo(LocalDate.of(2022, 3, 1));
        assertThat(range[1]).isEqualTo(LocalDate.now().withDayOfMonth(1));
    }

    @Test
    void shouldTolerateInlineNoiseDates() {
        // "其间 2020.06 转岗" 不应影响起止判断
        assertThat(ResumeDateParser.parseRange("2020.01 - 2021.02（其间 2020.06 转岗）"))
                .containsExactly(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 2, 1));
    }

    @Test
    void shouldIgnoreInvalidDatesAndUnparseableText() {
        assertThat(ResumeDateParser.parseRange("2020.13-2021.02")).isNull();
        assertThat(ResumeDateParser.parseRange("没有日期")).isNull();
        assertThat(ResumeDateParser.parseRange(null)).isNull();
    }

    @Test
    void shouldNormalizeInPlace() {
        assertThat(ResumeDateParser.normalize("2020年3月-2021年5月")).isEqualTo("2020.03-2021.05");
        assertThat(ResumeDateParser.normalize("2022.3 至今")).isEqualTo("2022.03 至今");
        assertThat(ResumeDateParser.normalize("无日期")).isEqualTo("无日期");
    }

    @Test
    void hasDatePatternShouldRecognizeChineseFormat() {
        assertThat(ResumeDateParser.hasDatePattern("2020年3月")).isTrue();
        assertThat(ResumeDateParser.hasDatePattern("2020.03")).isTrue();
        assertThat(ResumeDateParser.hasDatePattern("hello world")).isFalse();
    }
}
