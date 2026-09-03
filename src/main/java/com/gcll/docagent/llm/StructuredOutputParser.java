package com.gcll.docagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * LLM 结构化输出处理：自动生成 schema 提示 + 容错解析。
 *
 * <h3>两阶段设计（优化2）</h3>
 * <ol>
 *   <li><b>提示生成</b>：{@link #formatInstructions(Class)} 用 Spring AI 的 {@link BeanOutputConverter}
 *       自动从目标类生成 "请输出如下 JSON 格式：{schema}" 的提示文本，注入到 prompt 里。
 *       替代之前手写的 JSON 说明——自动跟随 Java 类字段变化，不用人维护。</li>
 *   <li><b>容错解析</b>：{@link #parse(String, Class)} 剥 markdown 代码块后用 Jackson 反序列化。
 *       LLM 输出不保证纯 JSON（会夹 ```json ``` 代码块、多余文字），这里容错处理而非直接抛异常。</li>
 * </ol>
 *
 * <p>不用 Spring AI 的 {@code ChatClient.entity()} 的原因：那是个黑盒，遇到非法 JSON 直接抛异常，
 * 无法降级。本类把"生成 schema 提示"和"容错解析"拆开，前者复用 Spring AI 能力，后者自己掌控鲁棒性。
 */
@Component
public class StructuredOutputParser {

    private final ObjectMapper objectMapper;

    public StructuredOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从目标类自动生成"请输出如下 JSON 格式"的提示文本，注入 prompt。
     *
     * <p>用 Spring AI 的 {@link BeanOutputConverter} 生成 schema，跟随 Java 类字段变化，
     * 替代手写 JSON 说明。调用方拼 prompt 时拼接本方法返回值。
     *
     * @param type 目标输出类（如 CriticJson.class）
     * @return 形如 "Your response should be in JSON format: {schema}" 的提示文本
     */
    public <T> String formatInstructions(Class<T> type) {
        // 每次新建（轻量），避免跨调用缓存导致 schema 过期
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type, objectMapper);
        return converter.getFormat();
    }

    /**
     * 容错解析 LLM 输出为对象。剥 markdown 代码块后用 Jackson 反序列化。
     *
     * @param json LLM 原始输出（可能含 ```json ``` 代码块）
     * @param type 目标类
     * @return 解析后的对象
     * @throws LlmParseException 解析失败（调用方应 catch 走降级）
     */
    public <T> T parse(String json, Class<T> type) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            return objectMapper.readValue(cleaned, type);
        } catch (Exception ex) {
            throw new LlmParseException("Failed to parse LLM JSON output: " + ex.getMessage());
        }
    }
}
