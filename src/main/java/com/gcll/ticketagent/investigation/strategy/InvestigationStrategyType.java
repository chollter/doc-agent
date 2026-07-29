package com.gcll.ticketagent.investigation.strategy;

/**
 * 排查策略枚举
 * 
 * 策略选择逻辑：
 * - CONSULT：只查 RAG，证据明确，P2/P3 优先级
 * - LINEAR：线性路径，证据明确但需要深入分析，P1 优先级
 * - REACT：LLM 自主调工具迭代推理，证据不明确，P0 优先级
 * - MULTI：多 Agent 协作，复杂场景，跨团队协作
 */
public enum InvestigationStrategyType {
    
    /**
     * 咨询模式：只查 RAG
     * 适用场景：证据明确，P2/P3 优先级，有明确解决方案
     */
    CONSULT("consult", "咨询模式 - RAG 查询"),
    
    /**
     * 线性模式：按步骤深入分析
     * 适用场景：证据明确但需要深入分析，P1 优先级
     */
    LINEAR("linear", "线性模式 - 步骤化分析"),
    
    /**
     * ReAct 模式：LLM 自主调工具迭代推理
     * 适用场景：证据不明确，需要探索性分析，P0 优先级
     */
    REACT("react", "ReAct 模式 - 工具迭代推理"),
    
    /**
     * 多 Agent 模式：团队协作
     * 适用场景：复杂场景，跨团队协作，需要多个 Agent 协同
     */
    MULTI("multi", "多 Agent 模式 - 团队协作");
    
    private final String code;
    private final String description;
    
    InvestigationStrategyType(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 根据优先级和场景选择策略
     */
    public static InvestigationStrategyType fromPriorityAndIssueType(String priority, String issueType) {
        // P0 优先级：使用 ReAct 模式（最灵活）
        if ("P0".equals(priority)) {
            return REACT;
        }
        
        // P1 优先级：线性模式（结构化分析）
        if ("P1".equals(priority)) {
            return LINEAR;
        }
        
        // P2/P3 优先级：咨询模式（快速 RAG）
        if ("P2".equals(priority) || "P3".equals(priority)) {
            return CONSULT;
        }
        
        // 默认使用咨询模式
        return CONSULT;
    }
}