package com.gcll.docagent.resilience;

/**
 * LLM 执行结果：内容 + token 用量 + 实际使用的模型名。
 * <p>token 缺失（部分模型/流式不返回 usage）时记 0。
 *
 * <p><b>model 字段</b>：阶段4 token 指标加 model 维度后需要透传实际模型名
 * （多模型路由后，需能定位"哪个 callName 费 token、用的哪个模型"）。
 * 老路径调用方拿不到模型名时用 {@link #of(String, int, int)} 兜底为 "unknown"。
 */
public record LlmResponse(
        String content,
        int promptTokens,
        int completionTokens,
        String model
) {

    /** 全参构造的工厂：多模型路由路径用，model 来自 ChatResponseMetadata。 */
    public static LlmResponse of(String content, int promptTokens, int completionTokens, String model) {
        return new LlmResponse(content, promptTokens, completionTokens, model);
    }

    /** 三参兜底：model 维度缺失时记 "unknown"（老路径 / 测试桩）。 */
    public static LlmResponse of(String content, int promptTokens, int completionTokens) {
        return new LlmResponse(content, promptTokens, completionTokens, "unknown");
    }
}
