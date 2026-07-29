package com.gcll.ticketagent.api;

import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.ticket.TicketApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiExceptionHandlerTest extends com.gcll.ticketagent.testsupport.RedisIsolatedTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TicketApplicationService ticketApplicationService;

    @Test
    void returnsNotFoundForMissingAgentRun() throws Exception {
        mockMvc.perform(get("/api/tickets/agent-runs/missing-id").header("X-Trace-Id", "trace-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("trace-1"));
    }

    @Test
    void exposesAgentRunAudit() throws Exception {
        var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                "sess-audit", "u3003", "", "接口报错了", "WEB", null, null
        ));

        mockMvc.perform(get("/api/audit/agent-runs/" + response.runId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$[0].stepName", notNullValue()));
    }

    @Test
    void exposesAgentRunLlmStats() throws Exception {
        var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                "sess-llm-stats", "u3003", "", "接口报错了", "WEB", null, null
        ));

        mockMvc.perform(get("/api/audit/agent-runs/" + response.runId() + "/llm-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(response.runId()))
                .andExpect(jsonPath("$.totalCalls", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byCallName", notNullValue()));
    }

    @Test
    void returnsValidationErrorCode() throws Exception {
        mockMvc.perform(post("/api/tickets/agent-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
