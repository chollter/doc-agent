package com.gcll.ticketagent.platform.tool;

import com.gcll.ticketagent.extract.TicketExtractResult;

public record ToolExecutionContext(
        String runId,
        String phase,
        TicketExtractResult extract,
        String originalContent
) {
}
