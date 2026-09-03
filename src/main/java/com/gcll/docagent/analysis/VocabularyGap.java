package com.gcll.docagent.analysis;

/**
 * 词汇覆盖缺口——简历做了但没用行业术语表述导致的检索损失。
 * <p>只建议"同义词→术语"的表述升级，同义词未出现时不建议（防关键词造假）。
 */
public record VocabularyGap(String term, String usedSynonym, String suggestion) {
}
