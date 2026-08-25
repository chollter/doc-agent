package com.gcll.docagent.tool.document;

import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.tool.ToolConfirmGate;
import com.gcll.docagent.tool.ToolDescriptor;
import com.gcll.docagent.tool.ToolGateway;
import com.gcll.docagent.tool.ToolInvocation;
import com.gcll.docagent.tool.ToolResult;
import com.gcll.docagent.tool.ToolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DANGER 工具：把最终报告导出为 Markdown 文件。
 * <p>演示人工确认闭环的落点——执行前被 {@link ToolConfirmGate} 拦截：
 * 写 pending_action → run 进入 WAIT_HUMAN_CONFIRM → 前端弹确认卡 →
 * 人工确认/拒绝后放行或跳过，全程在 trace 与审计里可见。
 */
@Component
public class ExportReportTool implements ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ExportReportTool.class);
    private static final long CONFIRM_TIMEOUT_MS = 5 * 60 * 1000;
    private static final long POLL_INTERVAL_MS = 1500;

    private final ToolConfirmGate confirmGate;
    private final AgentRunRepository agentRunRepository;
    private final Path exportDir;

    public ExportReportTool(ToolConfirmGate confirmGate,
                            AgentRunRepository agentRunRepository,
                            @Value("${docagent.analysis.export-dir:./data/exports}") String exportDir) {
        this.confirmGate = confirmGate;
        this.agentRunRepository = agentRunRepository;
        this.exportDir = Path.of(exportDir);
    }

    @Override
    public ToolType toolType() {
        return ToolType.DANGER_FUNCTION;
    }

    @Override
    public String toolName() {
        return "export_report";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String filename = sanitize(invocation.param("filename"));
        String content = invocation.param("content");
        if (filename == null || content == null || content.isBlank()) {
            return failure(invocation, "缺少参数 filename / content");
        }
        String runId = invocation.runId();

        // 1. 门控拦截：写 pending_action，等待人工决策
        String payload = "导出文件：" + filename + "\n\n" + content;
        ToolConfirmGate.ConfirmDecision decision =
                confirmGate.requestConfirm(descriptor(), runId, payload);

        // 2. 阻塞等待确认（run 置为 WAIT_HUMAN_CONFIRM，前端轮询可见）
        updateRunStatus(runId, AgentRunStatus.WAIT_HUMAN_CONFIRM);
        ToolConfirmGate.ConfirmStatus status = awaitConfirmation(runId, decision.actionId());
        updateRunStatus(runId, AgentRunStatus.ANALYZING);

        return switch (status) {
            case CONFIRMED -> doWrite(invocation, runId, filename, content);
            case REJECTED -> failure(invocation, "人工已拒绝导出，未写入文件。请直接输出最终 JSON 报告。");
            default -> failure(invocation, "等待人工确认超时（5 分钟），未写入文件。请直接输出最终 JSON 报告。");
        };
    }

    private ToolConfirmGate.ConfirmStatus awaitConfirmation(String runId, String actionId) {
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ToolConfirmGate.ConfirmStatus status = confirmGate.checkStatus(runId, toolName());
            if (status == ToolConfirmGate.ConfirmStatus.CONFIRMED
                    || status == ToolConfirmGate.ConfirmStatus.REJECTED) {
                return status;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ToolConfirmGate.ConfirmStatus.PENDING;
            }
        }
        return ToolConfirmGate.ConfirmStatus.PENDING;
    }

    private ToolResult doWrite(ToolInvocation invocation, String runId, String filename, String content) {
        try {
            Files.createDirectories(exportDir);
            Path target = exportDir.resolve(runId + "-" + filename);
            Files.writeString(target, content == null ? "" : content);
            log.info("Report exported (human confirmed), runId={}, file={}", runId, target);
            return ToolResult.success(toolType(), toolName(),
                    "filename=" + filename,
                    "【已确认并导出】报告已写入 " + target, 0);
        } catch (IOException ex) {
            return failure(invocation, "写入文件失败: " + ex.getMessage());
        }
    }

    private void updateRunStatus(String runId, AgentRunStatus status) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            // 只在运行中/等待确认之间切换，不覆盖终态
            AgentRunStatus current = run.getStatus();
            if (current == AgentRunStatus.COMPLETED || current == AgentRunStatus.FAILED) {
                return;
            }
            run.setStatus(status);
            agentRunRepository.save(run);
        });
    }

    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String name = filename.trim().replace("\\", "/").replace("/", "-");
        if (!name.endsWith(".md")) {
            name = name + ".md";
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private ToolResult failure(ToolInvocation invocation, String message) {
        return ToolResult.failure(toolType(), toolName(),
                "filename=" + invocation.param("filename"), message, 0);
    }
}
