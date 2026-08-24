package com.gcll.ticketagent.execution.evidence;

import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.platform.tool.ToolCallRequest;
import com.gcll.ticketagent.platform.tool.ToolExecutionContext;
import com.gcll.ticketagent.platform.tool.ToolRuntime;
import com.gcll.ticketagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EvidenceCollectionService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollectionService.class);
    private static final String PHASE = "EVIDENCE_COLLECTION";

    private final ToolRuntime toolRuntime;

    public EvidenceCollectionService(ToolRuntime toolRuntime) {
        this.toolRuntime = toolRuntime;
    }

    public List<ToolResult> collect(
            String runId,
            TicketExtractResult extract,
            String originalContent,
            ToolSelection selection
    ) {
        if (selection == null || selection.selectedToolNames() == null || selection.selectedToolNames().isEmpty()) {
            log.info("No tools selected for evidence collection, runId={}", runId);
            return List.of();
        }
        ToolExecutionContext context = new ToolExecutionContext(runId, PHASE, extract, originalContent);
        List<ToolCallRequest> requests = selection.selectedToolNames().stream()
                .map(toolName -> new ToolCallRequest(toolName, selection.parameters()))
                .toList();
        return toolRuntime.executeBatch(context, requests);
    }
}
