package com.gcll.docagent.analysis;

import java.util.List;

/** 简历分析的上下文——实体/红旗/画像/方向画像一次打包，贯穿执行链。 */
public record ResumeContext(
        ResumeEntities entities,
        List<RedFlag> redFlags,
        ResumeProfile profile,
        Archetype archetype,
        TargetProfile targetProfile,
        String matchMode,
        boolean degraded,
        String fullText,
        Persona persona,
        boolean ran
) {
    public static ResumeContext empty() {
        return new ResumeContext(new ResumeEntities(List.of()), List.of(), null, null, null,
                FunnelVerdict.MODE_NONE, false, "", Persona.GENERAL, false);
    }
}
