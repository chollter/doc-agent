package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量评估 prompt 与全量 prompt 的契约同步守卫——
 * 增量文件以全量契约为前缀原样内嵌（维度定义/文风/锚点逐字一致），追加块在其后。
 * 有人只改其一造成契约漂移时，本测试红灯。
 */
class ResumeEvaluationPromptContractTest {

    @Test
    void incrementalPromptEmbedsBaseContractVerbatim() throws Exception {
        String base = new ClassPathResource("prompts/resume-evaluation.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        String incremental = new ClassPathResource("prompts/resume-evaluation-incremental.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(incremental).contains(base).contains("增量评估模式");
    }
}
