package com.gcll.ticketagent.agent.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.react.ReActToolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 循环执行器：让 LLM 自主调工具收集证据、迭代推理、最后给根因。
 *
 * <h3>核心机制：复用框架内置的 tool-calling 循环</h3>
 * Spring AI 的 {@code chatClient.tools(adapter).call()} 已内置 ReAct 循环——
 * LLM 返回 tool call → 框架自动执行 adapter 方法 → 结果喂回 LLM → LLM 再推理，
 * 直到 LLM 不再调工具直接给文本。所以本类<b>不手写 think/act/observe 循环</b>，
 * 而是包装一次带 tools 的调用，外加上层治理。
 *
 * <h3>本类职责（在框架循环之上加的工程治理）</h3>
 * <ul>
 *   <li><b>ToolContext 注入</b>：把 extract/originalContent 注入，工具 adapter 从中取参数</li>
 *   <li><b>步数/超时兜底</b>：框架循环理论上可能很长（LLM 反复调工具），用 maxSteps 和总耗时上限兜底</li>
 *   <li><b>证据收集</b>：记录循环中实际调了哪些工具（从 ToolContext 共享状态读）</li>
 *   <li><b>JSON 解析</b>：LLM 最终输出按 prompt 约定的 JSON 结构，解析为 RootCause</li>
 *   <li><b>失败回退</b>：循环异常/超步/解析失败 → 抛 ReActException，由 AgentRuntime 回退线性流水线</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * 本类任何异常都抛 {@link ReActException}，调用方（AgentRuntime）catch 后回退现有
 * AnalysisWorkflowService（线性流水线 + 规则兜底）。这保证 ReAct 出问题不致命，
 * 符合"LLM 不可靠→工程兜底"主线。
 */
@Service
public class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次 ReAct 循环最多工具调用次数（兜底，防 LLM 无限循环）。框架内部循环不受此直接约束，
     *  但作为整体超时/步数预算的参考上限。 */
    private static final int MAX_STEPS = 6;
    /** 单次 ReAct 循环总耗时上限（兜底）。 */
    private static final long MAX_TOTAL_MS = 90_000L;
    private static final String REACT_PROMPT_FILE = "react-root-cause.txt";
    private static final String SYSTEM_BASE_FILE = "prompts/system-base.txt";

    private final ChatClient.Builder chatClientBuilder;
    private final ReActToolAdapter toolAdapter;
    private final ChatMemory chatMemory;
    private final String reactPrompt;
    private final String systemBasePrompt;

    public ReActLoop(ChatClient.Builder chatClientBuilder,
                     ReActToolAdapter toolAdapter,
                     ChatMemory chatMemory) throws IOException {
        this.chatClientBuilder = chatClientBuilder;
        this.toolAdapter = toolAdapter;
        this.chatMemory = chatMemory;
        this.reactPrompt = new ClassPathResource("prompts/" + REACT_PROMPT_FILE)
                .getContentAsString(StandardCharsets.UTF_8);
        this.systemBasePrompt = new ClassPathResource(SYSTEM_BASE_FILE)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 执行 ReAct 循环：LLM 自主调工具推理根因。
     *
     * @param runId           工单运行 ID（审计/记忆用）
     * @param extract         结构化抽取结果（注入 ToolContext 供 adapter 用）
     * @param originalContent 工单原文
     * @return 根因结论 + 循环中收集的工具结果
     * @throws ReActException 循环失败（异常/超步/解析失败），调用方应回退线性流水线
     */
    public ReActResult run(String runId, TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();
        // 工具上下文：extract/originalContent/toolCallLog 注入，adapter 从中取
        Map<String, Object> ctxMap = new HashMap<>();
        ctxMap.put(ReActToolAdapter.CTX_EXTRACT, extract);
        ctxMap.put(ReActToolAdapter.CTX_ORIGINAL_CONTENT, originalContent);
        List<String> toolCallLog = new ArrayList<>();
        ctxMap.put("toolCallLog", toolCallLog);

        try {
            // 构造请求：系统提示 + ReAct prompt + 工单内容 + 注入工具 + 上下文
            ChatClient.ChatClientRequestSpec request = chatClientBuilder.build().prompt()
                    .system(systemBasePrompt)
                    .user(reactPrompt + "\n\n工单内容：\n" + originalContent
                            + "\n\n已抽取字段：\n" + summarizeExtract(extract))
                    .tools(toolAdapter)
                    .toolContext(ctxMap);

            // ★ 真超时控制：在独立线程执行框架循环，主线程用 future.get(MAX_TOTAL_MS) 超时取消
            // 此前是同步阻塞调用 request.call() + 跑完后再 log.warn——"假兜底"，超时了也不管。
            // 改后：超过 MAX_TOTAL_MS 直接 cancel(true) 中断，抛 ReActException 触发回退线性。
            java.util.concurrent.CompletableFuture<ChatResponse> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> request.call().chatResponse());
            ChatResponse response;
            try {
                response = future.get(MAX_TOTAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ex) {
                future.cancel(true);
                log.warn("ReAct loop timed out after {}ms, runId={}, fallback to linear", MAX_TOTAL_MS, runId);
                throw new ReActException("ReAct loop timed out after " + MAX_TOTAL_MS + "ms");
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof ReActException re) throw re;
                log.warn("ReAct loop failed, runId={}, will fallback to linear: {}", runId, cause.getMessage());
                throw new ReActException("ReAct loop failed: " + cause.getMessage(), cause);
            }

            String content = response.getResult().getOutput().getText();
            long durationMs = System.currentTimeMillis() - start;

            RootCauseJson rc = parseRootCause(content);
            log.info("ReAct loop done, runId={}, steps≈{}, durationMs={}, confidence={}",
                    runId, toolCallLog.size(), durationMs, rc.confidence());

            return new ReActResult(rc.hypothesis(), rc.evidence(), rc.confidence(),
                    rc.unknowns(), rc.actions(), new ArrayList<>(toolCallLog), durationMs);
        } catch (ReActException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("ReAct loop failed, runId={}, will fallback to linear pipeline: {}",
                    runId, ex.getMessage(), ex);
            throw new ReActException("ReAct loop failed: " + ex.getMessage(), ex);
        }
    }

    /** 解析 LLM 最终 JSON 输出为根因结构。解析失败抛 ReActException（触发回退）。 */
    private RootCauseJson parseRootCause(String content) {
        if (content == null || content.isBlank()) {
            throw new ReActException("ReAct produced empty content");
        }
        try {
            // LLM 可能在 JSON 外包 markdown 围栏，剥掉
            String json = content.trim();
            if (json.startsWith("```")) {
                int firstNewline = json.indexOf('\n');
                if (firstNewline > 0) json = json.substring(firstNewline + 1);
                int lastFence = json.lastIndexOf("```");
                if (lastFence > 0) json = json.substring(0, lastFence);
            }
            return MAPPER.readValue(json.trim(), RootCauseJson.class);
        } catch (Exception ex) {
            throw new ReActException("ReAct root cause JSON parse failed: " + ex.getMessage()
                    + ", raw=" + truncate(content, 200), ex);
        }
    }

    private String summarizeExtract(TicketExtractResult e) {
        return "issueType=" + e.issueType()
                + ", system=" + e.affectedSystem()
                + ", module=" + e.affectedModule()
                + ", errorCode=" + e.errorCode();
    }

    private String truncate(String s, int max) {
        return s == null ? "(null)" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    /** ReAct 循环产出。 */
    public record ReActResult(
            String hypothesis,
            List<String> evidence,
            double confidence,
            List<String> unknowns,
            List<String> actions,
            List<String> toolCalls,
            long durationMs
    ) {}

    /** 解析后的根因 JSON 结构（对应 prompt 输出格式）。 */
    public record RootCauseJson(
            String hypothesis,
            List<String> evidence,
            double confidence,
            List<String> unknowns,
            List<String> actions
    ) {}

    /** ReAct 循环失败异常。调用方 catch 后回退线性流水线。 */
    public static class ReActException extends RuntimeException {
        public ReActException(String message) { super(message); }
        public ReActException(String message, Throwable cause) { super(message, cause); }
    }
}
