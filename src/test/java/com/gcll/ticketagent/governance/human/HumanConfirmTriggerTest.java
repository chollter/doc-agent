package com.gcll.ticketagent.governance.human;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.priority.TicketPriority;
import com.gcll.ticketagent.governance.risk.IncidentRiskPolicy;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HumanConfirmTriggerTest {

    private final HumanConfirmTrigger trigger = new HumanConfirmTrigger(new IncidentRiskPolicy());

    @Test
    void productionHighRiskIncidentRequiresHumanConfirm() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "结算系统", "批处理", null, null, "settlement job failed",
                "生产", "多个商户", "凌晨1点", "多个商户账单未生成",
                List.of(), 0.8
        );
        PriorityResult priority = new PriorityResult(
                TicketPriority.P2,
                List.of("DEFAULT_P2"),
                "一般优先级工单",
                false,
                false
        );
        RoutingResult routing = new RoutingResult("结算研发组", List.of(), "规则命中", 0.9);

        assertThat(trigger.needHumanConfirm(priority, routing, extract)).isTrue();
        assertThat(trigger.reason(priority, routing, extract)).contains("人工确认");
    }

    @Test
    void singleUserProductionIncidentCanAutoCompleteWhenRiskIsLow() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "账号系统", "MFA", "mfa/reset", null, "wait admin approval",
                "生产", "单个用户", "上午10点", "单个用户无法登录后台",
                List.of(), 0.8
        );
        PriorityResult priority = new PriorityResult(
                TicketPriority.P2,
                List.of("DEFAULT_P2"),
                "一般优先级工单",
                false,
                false
        );
        RoutingResult routing = new RoutingResult("账号研发组", List.of(), "规则命中", 0.9);

        assertThat(trigger.needHumanConfirm(priority, routing, extract)).isFalse();
    }

    @Test
    void productionCoreIncidentWithExplicitNonSingleImpactRequiresHumanConfirmEvenWhenPriorityDoesNot() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "库存系统", null, null, null, null,
                "生产", null, null, "订单付完了出库还没起，outbound 事件消费堵住",
                List.of(), 0.7
        );
        PriorityResult priority = new PriorityResult(
                TicketPriority.P2,
                List.of("DEFAULT_P2"),
                "一般优先级工单",
                false,
                false
        );
        RoutingResult routing = new RoutingResult("库存研发组", List.of(), "规则命中", 0.92);

        boolean need = trigger.needHumanConfirm(
                priority,
                routing,
                extract,
                "prod 库存这边像卡住了，订单明明付完了出库还没起，怀疑 outbound 事件消费堵住了，得看看 lag / dead letter。"
        );

        assertThat(need).isTrue();
    }

    @Test
    void noisyInventoryBacklogEvalShapeRequiresHumanConfirm() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "库存系统", "outbound 事件消费", null, null, null,
                "生产", "订单支付完成但出库未触发", null, null,
                List.of(), 0.85
        );
        PriorityResult priority = new PriorityResult(
                TicketPriority.P2,
                List.of("DEFAULT_P2"),
                "一般优先级工单",
                false,
                false
        );
        RoutingResult routing = new RoutingResult("库存系统组", List.of(), "LLM 智能决策", 0.92);

        String userContent = "噪声化库存事件积压\n"
                + "prod 库存这边像卡住了，订单明明付完了出库还没起，怀疑 outbound 事件消费堵住了，得看看 lag / dead letter。";

        assertThat(trigger.needHumanConfirm(priority, routing, extract, userContent)).isTrue();
        assertThat(trigger.reason(priority, routing, extract, userContent)).contains("生产环境 P2 故障");
    }
}
