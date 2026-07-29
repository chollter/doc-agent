package com.gcll.ticketagent.governance.human;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.priority.TicketPriority;
import com.gcll.ticketagent.governance.risk.IncidentRiskDecision;
import com.gcll.ticketagent.governance.risk.IncidentRiskPolicy;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import org.springframework.stereotype.Component;

@Component
public class HumanConfirmTrigger {

    private final IncidentRiskPolicy incidentRiskPolicy;

    public HumanConfirmTrigger(IncidentRiskPolicy incidentRiskPolicy) {
        this.incidentRiskPolicy = incidentRiskPolicy;
    }

    public boolean needHumanConfirm(PriorityResult priority, RoutingResult routing, TicketExtractResult extract) {
        return needHumanConfirm(priority, routing, extract, "");
    }

    public boolean needHumanConfirm(PriorityResult priority, RoutingResult routing, TicketExtractResult extract, String userContent) {
        if (priority != null && priority.needHumanConfirm()) {
            return true;
        }
        if (routing != null && routing.confidence() < 0.7) {
            return true;
        }
        if (extract != null && extract.confidence() < 0.5) {
            return true;
        }
        if (isProductionP2IncidentWithNonSingleImpact(priority, extract)) {
            return true;
        }
        if (isProductionCoreIncidentWithNonSingleImpact(extract, userContent)) {
            return true;
        }
        if (risk(extract, userContent).requireHumanConfirm()) {
            return true;
        }
        return false;
    }

    public String reason(PriorityResult priority, RoutingResult routing, TicketExtractResult extract) {
        return reason(priority, routing, extract, "");
    }

    public String reason(PriorityResult priority, RoutingResult routing, TicketExtractResult extract, String userContent) {
        if (priority != null && priority.needHumanConfirm()) {
            return priority.priority() + " 级生产故障需要人工确认后分派";
        }
        if (routing != null && routing.confidence() < 0.7) {
            return "团队路由置信度较低，需要人工确认";
        }
        if (extract != null && extract.confidence() < 0.5) {
            return "LLM 抽取置信度较低，需要人工确认";
        }
        if (isProductionP2IncidentWithNonSingleImpact(priority, extract)) {
            return "生产环境 P2 故障影响范围非单用户，需要人工确认后分派";
        }
        if (isProductionCoreIncidentWithNonSingleImpact(extract, userContent)) {
            return "生产环境核心链路故障影响范围非单用户，需要人工确认后分派";
        }
        IncidentRiskDecision risk = risk(extract, userContent);
        if (risk.requireHumanConfirm()) {
            return "生产环境高风险故障信号，需要人工确认后分派：" + risk.reasons();
        }
        return "需要人工确认";
    }

    private boolean isProductionP2IncidentWithNonSingleImpact(PriorityResult priority, TicketExtractResult extract) {
        if (priority == null || extract == null) {
            return false;
        }
        if (priority.priority() != TicketPriority.P2 || extract.issueType() != IssueType.INCIDENT) {
            return false;
        }
        if (!"生产".equals(extract.environment())) {
            return false;
        }
        String impactScope = extract.impactScope();
        return impactScope == null || !isSingleUserImpact(impactScope);
    }

    private boolean isProductionCoreIncidentWithNonSingleImpact(TicketExtractResult extract, String userContent) {
        if (extract == null || extract.issueType() != IssueType.INCIDENT) {
            return false;
        }
        String text = normalize(userContent) + " "
                + normalize(extract.affectedSystem()) + " "
                + normalize(extract.affectedModule()) + " "
                + normalize(extract.apiName()) + " "
                + normalize(extract.impactScope()) + " "
                + normalize(extract.businessImpact());
        if (!"生产".equals(extract.environment()) && !containsAny(text, "生产", "线上", "prod", "production")) {
            return false;
        }
        if (!hasExplicitNonSingleImpact(text, extract)) {
            return false;
        }
        return containsAny(text, "支付", "订单", "结算", "库存", "出库", "资金", "账务", "网关", "认证", "登录");
    }

    private boolean hasExplicitNonSingleImpact(String text, TicketExtractResult extract) {
        String impactScope = extract.impactScope();
        if (impactScope != null && isSingleUserImpact(impactScope)) {
            return false;
        }
        return impactScope != null && !impactScope.isBlank()
                || containsAny(text, "多个用户", "部分用户", "所有用户", "全量", "多个商户", "好几个用户");
    }

    private boolean isSingleUserImpact(String impactScope) {
        String text = normalize(impactScope);
        return containsAny(text, "单个用户", "单一用户", "单用户", "仅一个用户", "一个用户", "某个用户");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private IncidentRiskDecision risk(TicketExtractResult extract, String userContent) {
        return incidentRiskPolicy.evaluate(userContent, extract);
    }
}
