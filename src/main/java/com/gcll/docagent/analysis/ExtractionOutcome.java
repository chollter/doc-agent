package com.gcll.docagent.analysis;

/**
 * 实体抽取结果——携带来源与降级标记。
 * <p>P11 的降级路径（LLM 不可用时仅抽文件名当 ORGANIZATION）会继续产出"看起来正常"的评分，
 * 调用方无法区分"简历差"和"抽取降级"。P12 起降级显式标记，降级时不出分。
 */
public record ExtractionOutcome(ResumeEntities entities, Source source) {

    public enum Source { LLM, FALLBACK }

    public boolean degraded() {
        return source == Source.FALLBACK;
    }

    public static ExtractionOutcome llm(ResumeEntities entities) {
        return new ExtractionOutcome(entities, Source.LLM);
    }

    public static ExtractionOutcome fallback(ResumeEntities entities) {
        return new ExtractionOutcome(entities, Source.FALLBACK);
    }
}
