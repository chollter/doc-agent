package com.gcll.docagent.analysis;

/** JD 标准化的结果及其可靠性。 */
public record TargetProfileOutcome(TargetProfile profile, boolean degraded) {
    public static TargetProfileOutcome fallback() {
        return new TargetProfileOutcome(TargetProfile.jdFallback(), true);
    }
}
