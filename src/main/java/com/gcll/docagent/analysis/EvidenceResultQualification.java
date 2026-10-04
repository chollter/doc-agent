package com.gcll.docagent.analysis;

import java.util.Locale;
import java.util.regex.Pattern;

/** 小型确定性门槛：探索到的引文必须同时包含量化值和结果/影响语义，才可升级为结果证据。 */
final class EvidenceResultQualification {

    private static final Pattern QUANTITY = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:%|倍|万|千|个|人|家|次|条|秒|分钟|小时|天|ms|毫秒|GB|TB|QPS|TPS|元|万元|项)",
            Pattern.CASE_INSENSITIVE);
    private static final String[] RESULT_SIGNALS = {
            "提升", "降低", "减少", "增长", "缩短", "节省", "达到", "从", "至", "故障率", "成功率", "耗时", "性能", "成本", "用户", "吞吐"
    };

    private EvidenceResultQualification() {
    }

    static boolean isQuantifiedResult(String quote) {
        if (quote == null || !QUANTITY.matcher(quote).find()) return false;
        String normalized = quote.toLowerCase(Locale.ROOT);
        for (String signal : RESULT_SIGNALS) {
            if (normalized.contains(signal)) return true;
        }
        return false;
    }
}
