package com.gcll.ticketagent.triage;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.governance.priority.TicketPriority;

/**
 * 规则前置引擎——零 LLM 快速分类。
 * <p>
 * 用关键词/正则匹配工单内容，在 LLM 调用前先尝试分类。命中规则直接返回结果
 * （confidence=1.0），跳过后续 LLM 分类步骤，省 Token + 秒级响应。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>规则只做"高置信度快速命中"，不做模糊猜测——宁可漏给 LLM，不要误分类</li>
 *   <li>规则内容按业务领域维护（运维工单场景），可随业务扩展</li>
 *   <li>优先级也走规则：P0 信号（生产+核心+全量）直接判定，不依赖 LLM</li>
 * </ul>
 */
public class RuleBasedTriageEngine {

    /**
     * 尝试规则前置分类。
     *
     * @param content 工单原始内容
     * @return 规则匹配结果，null 表示规则未命中（需走 LLM 路径）
     */
    public TriageResult triage(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        String lower = content.toLowerCase();

        // 1. IssueType 规则匹配
        IssueType issueType = classifyIssueType(lower);
        if (issueType == null) {
            return null; // 规则未命中，交给 LLM
        }

        // 2. 优先级规则匹配
        TicketPriority priority = classifyPriority(lower);

        // 3. 系统/模块提取
        String system = extractSystem(lower);
        String module = extractModule(lower);

        // 4. P0/P1 标记人工确认
        boolean needHumanConfirm = priority == TicketPriority.P0 || priority == TicketPriority.P1;

        return TriageResult.builder()
                .issueType(issueType)
                .priority(priority)
                .confidence(1.0)
                .source(TriageResult.TriageSource.RULE)
                .affectedSystem(system)
                .affectedModule(module)
                .needHumanConfirm(needHumanConfirm)
                .followUpRound(0)
                .build();
    }

    // --- IssueType 规则 ---

    private IssueType classifyIssueType(String lower) {
        // INCIDENT：生产故障关键词
        if (containsAny(lower,
                "生产故障", "线上故障", "宕机", "服务不可用", "502", "503", "504",
                "oom", "oomkilled", "connection refused", "超时", "响应慢",
                "全量不可用", "核心链路", "交易失败")) {
            return IssueType.INCIDENT;
        }

        // PERMISSION：权限/访问相关
        if (containsAny(lower,
                "权限", "无权访问", "access denied", "forbidden", "403",
                "授权", "角色", "审批", "开通权限")) {
            return IssueType.PERMISSION;
        }

        // DATA：数据问题
        if (containsAny(lower,
                "数据不一致", "数据丢失", "数据错乱", "脏数据", "对账不平",
                "金额不符", "订单状态不对", "库存不准")) {
            return IssueType.DATA;
        }

        // CONSULT：咨询类（最弱信号，放在最后）
        if (containsAny(lower,
                "如何", "怎么操作", "使用方法", "帮忙看看", "想了解",
                "咨询", "文档在哪", "是否有", "能不能支持")) {
            return IssueType.CONSULT;
        }

        // REQUIREMENT：需求类
        if (containsAny(lower,
                "新需求", "功能需求", "希望增加", "产品需求", "迭代需求")) {
            return IssueType.REQUIREMENT;
        }

        return null; // 规则未命中
    }

    // --- 优先级规则 ---

    private TicketPriority classifyPriority(String lower) {
        // P0 信号：生产+核心+全量
        if (containsAll(lower, "生产") &&
            containsAny(lower, "全量", "所有用户", "核心链路", "核心交易", "核心支付") &&
            containsAny(lower, "不可用", "宕机", "502", "503", "失败", "无法")) {
            return TicketPriority.P0;
        }

        // P1 信号：生产+多用户受影响
        if (containsAll(lower, "生产") &&
            containsAny(lower, "多用户", "部分用户", "大量", "批量")) {
            return TicketPriority.P1;
        }

        // P1 信号：资金不一致
        if (containsAny(lower, "资金", "对账不平", "金额不符", "数据不一致") &&
            containsAll(lower, "生产")) {
            return TicketPriority.P1;
        }

        // P3 信号：测试环境
        if (containsAll(lower, "测试") || containsAll(lower, "开发")) {
            return TicketPriority.P3;
        }

        return TicketPriority.P2; // 默认 P2
    }

    // --- 系统/模块提取 ---

    private String extractSystem(String lower) {
        // 常见系统名映射（运维场景）
        String[][] mappings = {
                {"支付", "支付系统"}, {"payment", "支付系统"},
                {"订单", "订单系统"}, {"order", "订单系统"},
                {"用户", "用户系统"}, {"user", "用户系统"},
                {"账号", "账号系统"}, {"account", "账号系统"},
                {"结算", "结算系统"}, {"settlement", "结算系统"},
                {"风控", "风控系统"}, {"risk", "风控系统"},
                {"消息", "消息平台"}, {"mq", "消息平台"},
                {"网关", "网关系统"}, {"gateway", "网关系统"},
                {"配置", "配置中心"}, {"config", "配置中心"},
        };
        for (String[] pair : mappings) {
            if (lower.contains(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    private String extractModule(String lower) {
        String[][] mappings = {
                {"回调", "回调模块"}, {"callback", "回调模块"},
                {"批处理", "批处理模块"}, {"batch", "批处理模块"},
                {"hikari", "连接池"}, {"连接池", "连接池"},
                {"redis", "缓存"}, {"缓存", "缓存"},
                {"数据库", "数据库"}, {"database", "数据库"},
                {"鉴权", "鉴权模块"}, {"auth", "鉴权模块"},
        };
        for (String[] pair : mappings) {
            if (lower.contains(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    // --- 工具方法 ---

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private boolean containsAll(String text, String... keywords) {
        for (String kw : keywords) {
            if (!text.contains(kw)) return false;
        }
        return true;
    }
}
