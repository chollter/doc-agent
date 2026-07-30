package com.gcll.ticketagent.analysis;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class RuleBasedRootCauseAnalysisService implements RootCauseAnalysisService {

    @Override
    public RootCauseResult analyze(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults) {
        if (extract.issueType() == IssueType.CONSULT) {
            return analyzeConsult(extract, hits);
        }
        String originalContent = buildCombinedContent(extract, toolResults);

        if (isOomPattern(originalContent)) {
            return analyzeOom(extract, toolResults);
        }

        if (isConnectionTimeoutPattern(originalContent)) {
            return analyzeConnectionTimeout(extract, toolResults);
        }

        if (extract.affectedSystem() != null && extract.affectedSystem().contains("支付")) {
            return analyzePaymentIssue(extract, hits, toolResults);
        }

        return buildGenericResult(extract, hits, toolResults);
    }

    private boolean isOomPattern(String content) {
        return content.contains("oom") || content.contains("outofmemory")
                || content.contains("heap") || content.contains("memory")
                || content.contains("oomkilled");
    }

    private boolean isConnectionTimeoutPattern(String content) {
        return content.contains("timeout") || content.contains("connection")
                || content.contains("pool") || content.contains("hikari");
    }

    private RootCauseResult analyzeOom(TicketExtractResult extract, List<ToolResult> toolResults) {
        List<String> evidence = new ArrayList<>();
        evidence.add("工单描述涉及内存/OOM相关问题");

        for (ToolResult result : toolResults) {
            if (result.success() && result.output() != null) {
                if ("query_logs".equals(result.toolName())) {
                    evidence.add("日志证据: " + truncate(result.output(), 200));
                }
                if ("notifyOncall".equals(result.toolName())) {
                    evidence.add("已通知值班人: " + truncate(result.output(), 200));
                }
            }
        }

        List<String> unknowns = List.of("内存泄漏还是流量突增需进一步确认", "JVM 堆参数配置是否合理");

        return new RootCauseResult(
                "容器内存超限导致 OOMKilled，Pod 被内核终止",
                evidence,
                unknowns,
                0.75,
                false
        );
    }

    private RootCauseResult analyzeConnectionTimeout(TicketExtractResult extract, List<ToolResult> toolResults) {
        List<String> evidence = new ArrayList<>();
        evidence.add("工单描述涉及超时/连接池问题");

        for (ToolResult result : toolResults) {
            if (result.success() && result.output() != null) {
                if ("query_logs".equals(result.toolName())) {
                    evidence.add("日志证据: " + truncate(result.output(), 200));
                }
                if ("notifyOncall".equals(result.toolName())) {
                    evidence.add("已通知值班人: " + truncate(result.output(), 200));
                }
            }
        }

        List<String> unknowns = List.of("连接池耗尽是慢查询导致还是连接泄漏", "是否有近期的部署变更");

        return new RootCauseResult(
                "数据库连接池耗尽导致请求超时，后续请求排队等待",
                evidence,
                unknowns,
                0.70,
                false
        );
    }

    private RootCauseResult analyzePaymentIssue(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults) {
        List<String> evidence = new ArrayList<>();
        evidence.add("影响系统: " + extract.affectedSystem());
        if (extract.errorCode() != null) {
            evidence.add("错误码: " + extract.errorCode());
        }

        if (hits != null) {
            for (KnowledgeHit hit : hits) {
                evidence.add("历史案例[" + hit.sourceId() + "]: " + hit.summary());
            }
        }

        return new RootCauseResult(
                "支付回调接口异常，回调处理失败导致订单状态不一致",
                evidence,
                List.of("是否为上游支付渠道变更导致"),
                0.65,
                false
        );
    }

    private RootCauseResult analyzeConsult(TicketExtractResult extract, List<KnowledgeHit> hits) {
        List<String> evidence = new ArrayList<>();
        evidence.add("咨询类工单，已检索知识库配置说明");
        if (hits != null) {
            for (KnowledgeHit hit : hits) {
                evidence.add("知识来源[" + hit.sourceId() + "]: " + hit.summary());
            }
        }
        String module = extract.affectedModule() == null ? "相关功能" : extract.affectedModule();
        return new RootCauseResult(
                "用户咨询 " + module + " 配置方式，建议按知识库 SOP 引导操作",
                evidence,
                List.of("需确认用户当前账号角色与权限范围"),
                hits == null || hits.isEmpty() ? 0.55 : 0.75,
                false
        );
    }

    private RootCauseResult buildGenericResult(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults) {
        List<String> evidence = new ArrayList<>();
        if (extract.errorCode() != null) {
            evidence.add("错误码: " + extract.errorCode());
        }
        for (ToolResult result : toolResults) {
            if (result.success() && result.output() != null) {
                evidence.add(result.toolName() + ": " + truncate(result.output(), 150));
            }
        }
        if (hits != null) {
            for (KnowledgeHit hit : hits) {
                evidence.add("案例[" + hit.sourceId() + "]: " + hit.summary());
            }
        }

        return new RootCauseResult(
                "根因待确认，需结合更多日志进行排查",
                evidence,
                List.of("需确认具体的错误堆栈和触发条件"),
                0.3,
                false
        );
    }

    private String buildCombinedContent(TicketExtractResult extract, List<ToolResult> toolResults) {
        StringBuilder sb = new StringBuilder();
        sb.append(extract.toString().toLowerCase(Locale.ROOT));
        for (ToolResult result : toolResults) {
            if (result.success() && result.output() != null) {
                sb.append(" ").append(result.output().toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replace('\n', ' ').trim();
        return singleLine.length() <= maxLen ? singleLine : singleLine.substring(0, maxLen) + "...";
    }
}
