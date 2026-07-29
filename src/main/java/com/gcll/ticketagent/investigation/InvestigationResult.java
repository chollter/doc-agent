package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.triage.TriageResult;

/**
 * 排查结果——排查阶段的唯一输出。
 * <p>
 * 定位：Agent 做辅助不做决策。核心产出是证据包，初步诊断作为低置信度参考。
 * 人工接手后基于证据包做最终判断。
 *
 * @param runId            工单 ID
 * @param summary          排查摘要（一句话结论）
 * @param evidenceSummary  证据摘要（指纹化，不存原文）
 * @param hypothesis       初步假设（低置信度参考，不是最终结论）
 * @param suggestion       建议动作
 * @param needHumanConfirm 是否需要人工确认
 * @param confirmReason    人工确认原因
 * @param error            失败时的错误信息
 */
public record InvestigationResult(
        String runId,
        String summary,
        String evidenceSummary,
        String hypothesis,
        String suggestion,
        boolean needHumanConfirm,
        String confirmReason,
        String error
) {
    public boolean isSuccess() {
        return error == null;
    }

    public static InvestigationResult success(
            String runId, String summary, String evidenceSummary,
            String hypothesis, String suggestion,
            boolean needHumanConfirm, String confirmReason
    ) {
        return new InvestigationResult(
                runId, summary, evidenceSummary,
                hypothesis, suggestion,
                needHumanConfirm, confirmReason, null
        );
    }

    public static InvestigationResult failed(String runId, String error) {
        return new InvestigationResult(
                runId, null, null, null, null,
                false, null, error
        );
    }
}
