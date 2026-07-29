package com.gcll.ticketagent.resilience;

import com.gcll.ticketagent.cache.CacheLookup;
import com.gcll.ticketagent.cache.CacheType;
import com.gcll.ticketagent.cache.SemanticCacheService;
import com.gcll.ticketagent.llm.LlmGateway;
import com.gcll.ticketagent.metrics.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * LLM 调用执行器：封装 {@link ExternalCallGateway} 治理 + {@link LlmGateway} 纯执行 + token 埋点 + 语义缓存。
 * <p>业务层（extract / gap / planner / rootcause / suggestion / routing / follow-up / tool-select）
 * 统一通过本类调用 LLM，无需各自处理治理与埋点，避免 8 处重复代码。
 *
 * <p>token 埋点职责落在此处（而非通用的 {@link ExternalCallGateway}）：Gateway 是类型无关的
 * 治理层，不感知 LLM 特有的 token 概念；本执行器拿到 {@link LlmResponse} 后调
 * {@link CallMetrics#recordTokenUsage}，实现 spec §6.1 的 {@code ai_token_usage} 指标。
 *
 * <p>语义缓存（Day12 接入）：相同 promptFile + userContent 的调用可复用历史结果，
 * 省 Token + 加速重复工单。缓存通过 {@link SemanticCacheService} 管理，
 * 默认关闭（opsmind.cache.semantic.enabled=false），生产环境建议开启。
 */
@Component
public class LlmCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmCallExecutor.class);

    private final ExternalCallGateway gateway;
    private final CallMetrics metrics;
    private final AgentMetrics agentMetrics;
    private final LlmRunStatsRecorder runStatsRecorder;
    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final ObjectProvider<SemanticCacheService> cacheServiceProvider;

    public LlmCallExecutor(ExternalCallGateway gateway,
                           CallMetrics metrics,
                           AgentMetrics agentMetrics,
                           LlmRunStatsRecorder runStatsRecorder,
                           ObjectProvider<LlmGateway> llmGatewayProvider,
                           ObjectProvider<SemanticCacheService> cacheServiceProvider) {
        this.gateway = gateway;
        this.metrics = metrics;
        this.agentMetrics = agentMetrics;
        this.runStatsRecorder = runStatsRecorder;
        this.llmGatewayProvider = llmGatewayProvider;
        this.cacheServiceProvider = cacheServiceProvider;
    }

    /**
     * 按 callName 治理调用 LLM（加载 promptFile 提示词 + userContent），成功时埋点 token 用量。
     *
     * @param callName    对应 {@code opsmind.resilience.call-mappings} 的策略名（如 llm.ticket-extract）
     * @param promptFile  classpath:prompts/ 下的提示词文件名
     * @param userContent 用户工单内容
     * @return 治理后的调用结果；LlmGateway 不可用（无 ChatClient.Builder）时返回失败结果，调用方走降级
     */
    public CallResult<LlmResponse> execute(String callName, String promptFile, String userContent) {
        return execute(callName, promptFile, userContent, null);
    }

    /**
     * 带 runId 的治理调用（阶段A 接入记忆路径）。
     *
     * <p>runId 非 null 时走 {@link LlmGateway#invoke(String, String, String, String)} 4参新路径：
     * 按 callName 路由模型 + 以 runId 为 conversationId 启用记忆（经 SummarizingChatMemoryAdvisor）。
     * runId 为 null 时退回 2参老路径（无记忆），保持向后兼容。
     *
     * @param runId 工单运行 ID，作为记忆 conversationId；null 表示单轮（不挂记忆）
     */
    public CallResult<LlmResponse> execute(String callName, String promptFile, String userContent, String runId) {
        String effectiveRunId = runId != null ? runId : LlmRunContext.currentRunId();

        // 语义缓存：命中则直接返回，跳过 LLM 调用
        SemanticCacheService cacheService = cacheServiceProvider.getIfAvailable();
        if (cacheService != null) {
            String cacheKey = promptFile + "|" + userContent;
            CacheLookup cacheLookup = cacheService.lookup(CacheType.LLM, callName, cacheKey);
            if (cacheLookup.isHit()) {
                log.info("LLM semantic cache hit, callName={}, skipping LLM call", callName);
                agentMetrics.recordLlmCall(callName, true);
                LlmResponse cached = new LlmResponse(cacheLookup.value(), 0, 0, "cached");
                return CallResult.ok(cached, 0, 0L);
            }
        }

        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            CallResult<LlmResponse> result = CallResult.fail(
                    new NonRetryableCallException("LlmGateway bean not available (no ChatClient.Builder)"),
                    0, 0L);
            agentMetrics.recordLlmCall(callName, false);
            agentMetrics.recordFallback(callName);
            runStatsRecorder.record(effectiveRunId, callName, result);
            return result;
        }
        // runId 决定走记忆路径（4参）还是单轮路径（2参）
        CallResult<LlmResponse> result = runId != null
                ? gateway.execute(callName, () -> llmGateway.invoke(callName, promptFile, userContent, runId))
                : gateway.execute(callName, () -> llmGateway.invoke(promptFile, userContent));
        if (result.success()) {
            LlmResponse response = result.value();
            // 阶段4：按 callName + model 分维埋点，多模型路由后能定位"哪个调用费 token、用的哪个模型"
            metrics.recordTokenUsage(callName, response.model(), response.promptTokens(), response.completionTokens());

            // 语义缓存：未命中时写入缓存
            if (cacheService != null) {
                String cacheKey = promptFile + "|" + userContent;
                cacheService.put(CacheType.LLM, callName, cacheKey, response.content(), null, 0);
            }
        } else {
            agentMetrics.recordFallback(callName);
        }
        agentMetrics.recordLlmCall(callName, result.success());
        runStatsRecorder.record(effectiveRunId, callName, result);
        return result;
    }
}
