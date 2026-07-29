package com.gcll.ticketagent.api.dto;

import com.gcll.ticketagent.eval.EvalReport;

public record EvalReportExportRequest(
        EvalReport report,
        String outputPath
) {
}
