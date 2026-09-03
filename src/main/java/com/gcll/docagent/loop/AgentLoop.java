package com.gcll.docagent.loop;

import com.gcll.docagent.langchain4j.ReActContextHolder;
import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.persistence.entity.LlmInteractionEntity;
import com.gcll.docagent.persistence.mapper.LlmInteractionMapper;
import com.gcll.docagent.tool.ToolExecutionHolder;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private final boolean parallelTools;
    private final LlmInteractionMapper interactionMapper;

    /** 工具并行执行池（一轮内多个调用并发发出，如同时读 3 个节）。 */
    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "loop-tool");
        t.setDaemon(true);
        return t;
    });

    public AgentLoop(ChatModel chatModel,
                     LoopToolSpecs toolSpecs,
                     LoopCheckpointStore checkpointStore,
                     LlmInteractionMapper interactionMapper,
                     @Value("${docagent.analysis.loop.max-rounds:10}") int maxRounds,
                     @Value("${docagent.analysis.loop.max-tool-calls:16}") int maxToolCalls,
                     @Value("${docagent.analysis.loop.max-total-tokens:60000}") long maxTotalTokens,
                     @Value("${docagent.analysis.loop.parallel-tools:true}") boolean parallelTools) {
        this.chatModel = chatModel;
        this.toolSpecs = toolSpecs;
        this.checkpointStore = checkpointStore;
        this.interactionMapper = interactionMapper;
        this.budget = new LoopBudget(maxRounds, maxToolCalls, maxTotalTokens);
        this.parallelTools = parallelTools;
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

            // 记录 ReAct 循环每轮交互（优化证据链）
            recordLoopInteraction(ctx.runId(), state.round(), usage, ai.text(), hasToolCalls);

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

            // 4. ACTION + OBSERVATION：执行本轮全部工具调用（多个调用并行发出）
            List<ToolExecutionRequest> requests = ai.toolExecutionRequests();
            // 白名单防御：模型偶尔会幻觉调用本轮未声明的工具，直接执行会构成非法请求
            requests = requests.stream()
                    .filter(r -> ctx.toolNames().contains(r.name()))
                    .toList();
            if (requests.isEmpty()) {
                List<LoopMessage> withHint = new ArrayList<>(state.messages());
                withHint.add(LoopMessage.assistant(ai));
                withHint.add(LoopMessage.user("刚才的工具调用不在当前可用列表中。请仅使用可用工具，或直接输出最终 JSON。"));
                state = state.withMessages(List.copyOf(withHint)).advance(0);
                checkpointStore.save(ctx.runId(), state);
                continue;
            }
            List<String> results = executeToolBatch(ctx, requests);
            int executedThisRound = 0;
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest request = requests.get(i);
                if (state.toolCalls() + executedThisRound >= budget.maxToolCalls()) {
                    stopReason = "BUDGET_TOOL_CALLS";
                    break;
                }
                String observation = results.get(i);
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

    /**
     * 执行一批工具调用，返回与请求顺序一致的观察列表。
     * 并行路径把 runId/trace 上下文显式传播到工具线程（ThreadLocal 不随线程池传递）。
     */
    private List<String> executeToolBatch(LoopContext ctx, List<ToolExecutionRequest> requests) {
        List<String> results = new ArrayList<>(requests.size());
        if (!parallelTools || requests.size() <= 1) {
            for (ToolExecutionRequest request : requests) {
                results.add(toolSpecs.execute(ctx.runId(), request.name(), request.arguments()));
            }
            return results;
        }
        List<Future<String>> futures = new ArrayList<>(requests.size());
        for (ToolExecutionRequest request : requests) {
            futures.add(toolExecutor.submit(() -> {
                ToolExecutionHolder.setRunId(ctx.runId());
                ReActContextHolder.set(ctx.systemPrompt(), ctx.tracer(), ctx.parentStepId());
                try {
                    return toolSpecs.execute(ctx.runId(), request.name(), request.arguments());
                } finally {
                    ToolExecutionHolder.clear();
                    ReActContextHolder.clear();
                }
            }));
        }
        for (Future<String> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception ex) {
                results.add("【工具异常】" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        return results;
    }

    /** 记录 ReAct 循环每轮交互——用于优化证据链。失败不影响主流程。 */
    private void recordLoopInteraction(String runId, int round, TokenUsage usage,
                                       String responseText, boolean hasToolCalls) {
        try {
            LlmInteractionEntity entity = new LlmInteractionEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setRunId(runId);
            entity.setCallSite("REACT_ROUND_" + round);
            entity.setPromptTokens(0); // TokenUsage 不提供拆分，记 0
            entity.setCompletionTokens(usage != null && usage.totalTokenCount() != null
                    ? usage.totalTokenCount().intValue() : 0);
            entity.setFullResponse(responseText != null ? responseText : "[tool_calls]");
            entity.setSuccess(true);
            entity.setCreatedAt(LocalDateTime.now());
            interactionMapper.insert(entity);
        } catch (Exception ex) {
            log.debug("Failed to record loop interaction: {}", ex.getMessage());
        }
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
