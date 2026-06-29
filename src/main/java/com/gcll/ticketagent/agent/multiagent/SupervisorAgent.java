package com.gcll.ticketagent.agent.multiagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supervisor 子智能体：根据工单内容动态决定该跑哪些 Worker。
 *
 * <p>这是多 Agent 的"任务拆解"环节——不是无脑把所有 worker 全跑一遍，而是 LLM 根据工单
 * 类型/现象判断"这个工单需要查什么、不需要查什么"。
 *
 * <h3>解决的问题</h3>
 * 固定全跑的问题：咨询类工单（如"如何配置重试"）不需要查日志/指标，全跑是浪费；
 * 数据库类工单可能需要查的方面和支付类不同。智能拆解让调查有的放矢——省调用、避免无关
 * worker 干扰 Critic 判断。
 *
 * <h3>降级</h3>
 * LLM 拆解失败/不可用时，退化为"全量调度"（跑所有 worker）——不比现状差。
 * 这呼应项目主线：LLM 不可靠→工程兜底。
 */
@Component
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);
    private static final String PROMPT_FILE = "supervisor-decide.txt";
    private static final String CALL_NAME = "llm.supervisor";

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;

    public SupervisorAgent(LlmCallExecutor llmCallExecutor, StructuredOutputParser parser) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
    }

    /**
     * 根据工单内容决定该跑哪些 worker。
     *
     * @param originalContent   工单原文
     * @param extract           结构化抽取结果（类型/系统/模块）
     * @param availableWorkers  所有可用 worker（role → worker 实例）
     * @return 被选中的 worker 列表（至少 1 个）；LLM 失败时返回全部（兜底）
     */
    public List<WorkerAgent> decide(String originalContent, TicketExtractResult extract,
                                    List<WorkerAgent> availableWorkers) {
        if (availableWorkers.size() <= 1) {
            // 只有一个 worker 没什么可拆的，直接跑
            return availableWorkers;
        }

        // 1. 拼 supervisor 输入：工单 + 可选 worker 清单
        Map<String, WorkerAgent> workerByRole = availableWorkers.stream()
                .collect(Collectors.toMap(WorkerAgent::role, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
        String input = buildInput(originalContent, extract, new ArrayList<>(workerByRole.keySet()));

        // 2. 调 LLM 决定跑哪些
        CallResult<LlmResponse> result = llmCallExecutor.execute(CALL_NAME, PROMPT_FILE, input);
        if (!result.success() || result.value() == null) {
            log.warn("Supervisor LLM failed, fallback to all workers, runId content length={}", originalContent.length());
            return availableWorkers;  // 降级：全跑
        }
        try {
            DecideJson json = parser.parse(result.value().content(), DecideJson.class);
            List<WorkerAgent> selected = selectWorkers(json.selectedWorkers(), workerByRole);
            if (selected.isEmpty()) {
                log.warn("Supervisor selected no workers, fallback to all");
                return availableWorkers;  // 降级：全跑
            }
            log.info("Supervisor selected workers: {} (reason: {})",
                    selected.stream().map(WorkerAgent::role).toList(),
                    json.reason());
            return selected;
        } catch (Exception ex) {
            log.warn("Supervisor parse failed, fallback to all: {}", ex.getMessage());
            return availableWorkers;  // 降级：全跑
        }
    }

    /** 按 LLM 选的 role 清单过滤 worker，去重 + 保持注册顺序。 */
    private List<WorkerAgent> selectWorkers(List<String> selectedRoles, Map<String, WorkerAgent> workerByRole) {
        if (selectedRoles == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<WorkerAgent> result = new ArrayList<>();
        for (String role : selectedRoles) {
            if (role == null || role.isBlank()) continue;
            String trimmed = role.trim();
            if (workerByRole.containsKey(trimmed) && seen.add(trimmed)) {
                result.add(workerByRole.get(trimmed));
            }
        }
        return result;
    }

    private String buildInput(String originalContent, TicketExtractResult extract, List<String> roles) {
        StringBuilder workerList = new StringBuilder();
        for (String role : roles) {
            workerList.append("- ").append(role).append(": ").append(roleDescription(role)).append("\n");
        }
        String formatHint = parser.formatInstructions(DecideJson.class);
        return "【工单内容】\n" + originalContent
                + "\n\n【抽取字段】issueType=" + extract.issueType()
                + ", system=" + extract.affectedSystem()
                + ", module=" + extract.affectedModule()
                + ", errorCode=" + extract.errorCode()
                + "\n\n【可选调查员】\n" + workerList
                + "\n" + formatHint;
    }

    /** 给 supervisor 解释每个 worker 干啥（帮它判断该不该选）。 */
    private String roleDescription(String role) {
        return switch (role) {
            case "log" -> "查实时运维日志，定位错误位置/堆栈（适合故障排查）";
            case "metric" -> "查运行指标（CPU/内存/QPS），判断资源瓶颈（适合性能/容量问题）";
            case "knowledge" -> "查历史相似案例，提供可参考的根因与处置经验（通用，咨询/故障都适合）";
            default -> "调查 " + role;
        };
    }

    /** Supervisor LLM 输出结构。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecideJson(
            List<String> selectedWorkers,
            String reason
    ) {}
}
