package com.gcll.docagent.loop;

import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.parsing.ParsedDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自研 ReAct 执行内核——显式状态机 THOUGHT→ACTION→OBSERVATION。
 * <p>为什么不用框架的 AiService 循环（本内核解决的三件事）：
 * <ol>
 *   <li><b>崩溃恢复</b>：每轮 checkpoint 落库（消息+预算+文档快照），进程被杀后可从断点续跑；</li>
 *   <li><b>预算硬顶</b>：轮次/工具调用/token 三重上界，超限是可预期停止而非失控；</li>
 *   <li><b>降级不丢上下文</b>：循环失败时已收集的观察片段随结果带出，供 DIRECT_LLM 复用，
 *       而不是像框架循环那样整体作废、降级后重新读全文。</li>
 * </ol>
 * LangChain4j 在此只作为模型传输层（ChatModel + 消息类型），循环逻辑完全自有。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ChatModel chatModel;
    private final LoopToolSpecs toolSpecs;
    private final LoopCheckpointStore checkpointStore;
    private final LoopBudget budget;

    public AgentLoop(ChatModel chatModel,
                     LoopToolSpecs toolSpecs,
                     LoopCheckpointStore checkpointStore,
                     @Value("${docagent.analysis.loop.max-rounds:10}") int maxRounds,
                     @Value("${docagent.analysis.loop.max-tool-calls:16}") int maxToolCalls,
                     @Value("${docagent.analysis.loop.max-total-tokens:60000}") long maxTotalTokens) {
        this.chatModel = chatModel;
        this.toolSpecs = toolSpecs;
        this.checkpointStore = checkpointStore;
        this.budget = new LoopBudget(maxRounds, maxToolCalls, maxTotalTokens);
    }

    /** 一次循环执行的完整输入。 */
    public record LoopContext(
            String runId,
            String skillName,
            List<String> toolNames,
            String systemPrompt,
            String userMessage,
            ParsedDocument document,
            TraceRecorder tracer,
            String parentStepId,
            LoopState resumeFrom
    ) {
    }

    /** 已观察到的内容片段——降级时随结果带出，避免重新读全文。 */
    public record ObservedFragment(String toolName, String args, String observation) {
    }

    /** 循环结果：成功带最终回答；失败/超限带 stopReason 与已收集片段。 */
    public record LoopResult(
            boolean success,
            String finalAnswer,
            String stopReason,
            List<ObservedFragment> observations,
            LoopState finalState,
            int rounds,
            int toolCalls,
            long tokensUsed
    ) {
    }

    public LoopResult run(LoopContext ctx) {
        LoopState state = ctx.resumeFrom() != null
                ? ctx.resumeFrom()
                : new LoopState(ctx.skillName(), List.of(
                        LoopMessage.system(ctx.systemPrompt()),
                        LoopMessage.user(ctx.userMessage())), 0, 0, 0, ctx.document());

        // 初始 checkpoint（含文档快照）：首轮模型调用前被杀也能恢复
        checkpointStore.save(ctx.runId(), state);

        List<ObservedFragment> observations = new ArrayList<>();
        String stopReason = null;

        while (true) {
            // 1. 预算检查（进入每轮前）
            String violation = budget.violation(state);
            if (violation != null) {
                stopReason = violation;
                break;
            }

            // 2. THOUGHT：调模型
            ChatResponse response;
            try {
                response = chatModel.chat(ChatRequest.builder()
                        .messages(toFrameworkMessages(state))
                        .toolSpecifications(toolSpecs.specsFor(ctx.toolNames()))
                        .build());
            } catch (Exception ex) {
                log.warn("Loop model call failed, runId={}: {}", ctx.runId(), ex.getMessage());
                stopReason = "MODEL_ERROR:" + ex.getClass().getSimpleName();
                break;
            }

            TokenUsage usage = response.metadata() != null ? response.metadata().tokenUsage() : null;
            if (usage != null && usage.totalTokenCount() != null) {
                state = state.withTokens(state.tokensUsed() + usage.totalTokenCount());
            }

            AiMessage ai = response.aiMessage();
            boolean hasToolCalls = ai.hasToolExecutionRequests();
            traceLlmRound(ctx, hasToolCalls, usage, state);

            // 3. 追加 ASSISTANT 消息
            List<LoopMessage> messages = new ArrayList<>(state.messages());
            messages.add(LoopMessage.assistant(ai));
            state = state.withMessages(List.copyOf(messages));

            if (!hasToolCalls) {
                // 5. 最终回答
                checkpointStore.save(ctx.runId(), state);
                return new LoopResult(true, ai.text(), "FINAL_ANSWER",
                        observations, state, state.round(), state.toolCalls(), state.tokensUsed());
            }

            // 4. ACTION + OBSERVATION：执行本轮全部工具调用
            int executedThisRound = 0;
            for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                if (state.toolCalls() + executedThisRound >= budget.maxToolCalls()) {
                    stopReason = "BUDGET_TOOL_CALLS";
                    break;
                }
                String observation = toolSpecs.execute(ctx.runId(), request.name(), request.arguments());
                observations.add(new ObservedFragment(request.name(), request.arguments(), observation));

                List<LoopMessage> withTool = new ArrayList<>(state.messages());
                withTool.add(LoopMessage.tool(request, observation));
                state = state.withMessages(List.copyOf(withTool));
                executedThisRound++;
            }
            state = state.advance(executedThisRound);

            // 每轮结束 checkpoint（崩溃恢复点）
            checkpointStore.save(ctx.runId(), state);

            if (stopReason != null) {
                break;
            }
        }

        // 超限/失败退出：片段保留给降级路径
        checkpointStore.save(ctx.runId(), state);
        return new LoopResult(false, null, stopReason, observations, state,
                state.round(), state.toolCalls(), state.tokensUsed());
    }

    private List<ChatMessage> toFrameworkMessages(LoopState state) {
        return state.messages().stream()
                .map(LoopMessage::toFrameworkMessage)
                .map(m -> (ChatMessage) m)
                .toList();
    }

    /** 每轮 LLM 响应记录一步 trace（与既有步名兼容：评测/前端直接可用）。 */
    private void traceLlmRound(LoopContext ctx, boolean hasToolCalls, TokenUsage usage, LoopState state) {
        TraceRecorder tracer = ctx.tracer();
        if (tracer == null || ctx.parentStepId() == null) {
            return;
        }
        String stepId = tracer.begin("REACT_LLM_RESPONSE", ctx.parentStepId());
        tracer.recordMeta(stepId, true, "AgentLoop");
        tracer.recordInput(stepId, TraceRecorder.fingerprint("messages", String.valueOf(state.messages().size())));
        tracer.end(stepId, "hasToolCall=" + hasToolCalls
                + (usage != null && usage.totalTokenCount() != null
                        ? ",tokens=" + usage.totalTokenCount() + ",used=" + state.tokensUsed()
                        : ""), null);
    }
}
