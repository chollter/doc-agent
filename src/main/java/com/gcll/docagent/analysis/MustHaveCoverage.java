package com.gcll.docagent.analysis;

/**
 * 共性要求覆盖——方向画像 mustHaves 的逐条对照结果。
 * MET=有证据满足 / PARTIAL=沾边但证据不足 / MISSING=简历中找不到证据。
 */
public record MustHaveCoverage(String requirementId, String requirement, Status status,
                               String evidence, String sectionId) {

    public enum Status { MET, PARTIAL, MISSING }
}
