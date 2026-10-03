package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真折叠账本集成测试（Phase 1 欠账兑现）：超长文档 + 正预算 → 溢出在<b>装配层</b>折叠并记账，
 * 而不是被 LlmGateway 终线字符级静默截断。审计端点可直接反查这份 FOLDED 账本。
 *
 * <p>同时钉住预算优先级：配置覆盖（800）优先于网关窗口派生（桩返回 50000）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "docagent.analysis.react-enabled=false",
        "docagent.analysis.context-budget-tokens=800"
})
class ContextBudgetFoldingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmGateway llmGateway;

    /** 远超 800 token 预算的文档：5 节 × 每节约 5000 字，fulltext 必然折叠。 */
    private static final String HUGE = buildHuge();

    private static String buildHuge() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append("# 第").append(i).append("章 方案细节\n\n");
            sb.append("本章节记录了方案细节与验证步骤，供回归折叠路径使用。".repeat(300));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    @Test
    void foldsOversizedDocumentAndRecordsLedger() throws Exception {
        String llmJson = """
                {"summary":"超长文档的折叠验证。",
                 "keyPoints":["装配层折叠"],
                 "risks":[],
                 "citations":[]}
                """;
        when(llmGateway.invokeStream(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(llmJson, 100, 50, "qwen-plus"));
        // 网关派生 50000，但配置覆盖 800 优先——账本里的 budget 应为 800
        when(llmGateway.budgetForCall(anyString(), anyString())).thenReturn(50000);

        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.md", "text/markdown", HUGE.getBytes(StandardCharsets.UTF_8));
        MvcResult accepted = mockMvc.perform(multipart("/api/analysis/runs")
                        .file(file)
                        .param("instruction", "提炼要点"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = objectMapper.readTree(accepted.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("runId").asText();

        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            MvcResult result = mockMvc.perform(get("/api/analysis/runs/" + runId))
                    .andExpect(status().isOk())
                    .andReturn();
            var node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(node.get("status").asText()).isEqualTo("COMPLETED");
        });

        MvcResult audit = mockMvc.perform(get("/api/audit/agent-runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        String steps = audit.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 第一份真折叠账本：预算=配置覆盖值（非派生 50000、非 unlimited），溢出段 FOLDED 并带 tokens 压缩比
        assertThat(steps)
                .contains("budget=800")
                .contains("FOLDED");
        // 折叠不静默：账本里折叠段的 after/before token 比可见（判断当时窗口装了什么的多少）
        assertThat(steps).containsPattern("FOLDED \\d+/\\d+");
    }
}
