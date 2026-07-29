package com.gcll.ticketagent.agent;

import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.HumanConfirmDto;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.RootCauseDto;
import com.gcll.ticketagent.api.dto.RoutingDto;
import com.gcll.ticketagent.api.dto.SuggestionDto;
import com.gcll.ticketagent.api.dto.TicketAnalysisDto;
import com.gcll.ticketagent.api.dto.TicketSummaryDto;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import com.gcll.ticketagent.suggestion.TicketSuggestion;
import com.gcll.ticketagent.triage.TriageResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentResponseAssembler {

    public AgentRunResponse needMoreInfo(String runId, List<String> questions, boolean aiGenerated) {
        return new AgentRunResponse(
                runId,
                AgentRunStatus.WAIT_USER_INPUT,
                ReplyType.NEED_MORE_INFO,
                "当前信息不足，暂时无法判断影响范围和责任团队。请补充以下信息：",
                questions,
                null,
                aiGenerated
        );
    }

    public AgentRunResponse analysisResult(
            String runId,
            TicketAnalysisDto analysis,
            boolean needConfirm,
            boolean aiGenerated
    ) {
        return new AgentRunResponse(
                runId,
                needConfirm ? AgentRunStatus.WAIT_HUMAN_CONFIRM : AgentRunStatus.FINAL,
                ReplyType.TICKET_ANALYSIS_RESULT,
                needConfirm ? "分析完成，等待人工确认后分派" : "分析完成",
                null,
                analysis,
                aiGenerated
        );
    }

    public AgentRunResponse failed(String runId) {
        return new AgentRunResponse(
                runId,
                AgentRunStatus.FAILED,
                ReplyType.TICKET_ANALYSIS_RESULT,
                "分析过程出现异常，请稍后重试或联系值班人员。",
                null,
                null,
                false
        );
    }

    /**
     * 分诊完成——同步返回分诊结果，排查异步进行。
     */
    public AgentRunResponse triageResult(String runId, TriageResult triageResult, boolean aiGenerated) {
        return new AgentRunResponse(
                runId,
                AgentRunStatus.INVESTIGATING,
                ReplyType.TRIAGE_RESULT,
                "分诊完成，工单已路由至" + (triageResult.routedTeam() != null ? triageResult.routedTeam() : "待分配团队")
                        + "，排查正在后台进行。",
                null,
                null,
                aiGenerated
        );
    }

    public TicketAnalysisDto buildAnalysis(
            TicketExtractResult extract,
            PriorityResult priority,
            RoutingResult routing,
            RootCauseResult rootCause,
            TicketSuggestion suggestion,
            boolean needConfirm,
            String confirmReason
    ) {
        TicketSummaryDto ticket = new TicketSummaryDto(
                suggestion.summary(),
                extract.issueType().name(),
                priority.priority().name(),
                extract.affectedSystem(),
                extract.affectedModule(),
                extract.impactScope()
        );
        RoutingDto routingDto = new RoutingDto(routing.primaryTeam(), routing.backupTeams(), routing.routingReason());
        RootCauseDto rootCauseDto = new RootCauseDto(
                rootCause.hypothesis(),
                rootCause.evidence(),
                rootCause.unknowns(),
                rootCause.confidence()
        );
        SuggestionDto suggestionDto = new SuggestionDto(
                suggestion.possibleCauses(),
                suggestion.actions(),
                suggestion.runbookSteps(),
                suggestion.sources()
        );
        HumanConfirmDto humanConfirm = new HumanConfirmDto(needConfirm, needConfirm ? confirmReason : null);
        return new TicketAnalysisDto(ticket, routingDto, rootCauseDto, suggestionDto, humanConfirm);
    }
}
