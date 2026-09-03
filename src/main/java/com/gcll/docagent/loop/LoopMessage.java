package com.gcll.docagent.loop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * 可序列化的循环消息——checkpoint 的载体，与具体框架的消息类型解耦；
 * 调用模型时按需转换为 LangChain4j 消息（框架只做传输层）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoopMessage(
        String role,            // SYSTEM / USER / ASSISTANT / TOOL
        String text,            // 文本内容（ASSISTANT/TOOL 可与工具调用并存或为 null）
        List<ToolCall> toolCalls, // ASSISTANT 发起的工具调用
        String toolCallId,      // TOOL 消息对应的调用 ID
        String toolName         // TOOL 消息对应的工具名
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(String id, String name, String arguments) {
    }

    public static LoopMessage system(String content) {
        return new LoopMessage("SYSTEM", content, null, null, null);
    }

    public static LoopMessage user(String content) {
        return new LoopMessage("USER", content, null, null, null);
    }

    public static LoopMessage assistant(AiMessage ai) {
        List<ToolCall> calls = ai.hasToolExecutionRequests()
                ? ai.toolExecutionRequests().stream()
                        .map(r -> new ToolCall(r.id(), r.name(), r.arguments()))
                        .toList()
                : null;
        return new LoopMessage("ASSISTANT", ai.text(), calls, null, null);
    }

    public static LoopMessage tool(ToolExecutionRequest request, String observation) {
        return new LoopMessage("TOOL", observation, null, request.id(), request.name());
    }

    public ChatMessage toFrameworkMessage() {
        return switch (role) {
            case "SYSTEM" -> SystemMessage.from(text);
            case "USER" -> UserMessage.from(text);
            case "ASSISTANT" -> AiMessage.builder()
                    .text(text)
                    .toolExecutionRequests(toolCalls == null ? List.of() : toolCalls.stream()
                            .map(c -> ToolExecutionRequest.builder()
                                    .id(c.id()).name(c.name()).arguments(c.arguments() == null ? "{}" : c.arguments())
                                    .build())
                            .toList())
                    .build();
            case "TOOL" -> ToolExecutionResultMessage.from(
                    ToolExecutionRequest.builder().id(toolCallId).name(toolName).arguments("{}").build(),
                    text);
            default -> throw new IllegalStateException("未知消息角色: " + role);
        };
    }
}
