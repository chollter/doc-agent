package com.gcll.docagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 评测用例——固定输入 + 结果断言，让"引用防幻觉""降级可靠"这类主张变得可度量。
 * <p>断言设计为模式无关（REACT/LLM/FALLBACK 皆须通过），保证无 API Key 的 CI 环境
 * 也能跑通全量回归；接入真实 Key 时同一批用例自动验证更强路径。
 *
 * @param name        用例名
 * @param file        classpath 下的文档路径（如 samples/tech-spec.md）
 * @param skill       技能名
 * @param instruction 分析指令
 * @param timeoutSeconds 单用例超时
 * @param assertions  结果断言
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String name,
        String file,
        String skill,
        String instruction,
        int timeoutSeconds,
        Assertions assertions
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assertions(
            String status,
            List<String> allowedModes,
            Integer minKeyPoints,
            Integer minCitations,
            List<String> keywords
    ) {
    }
}
