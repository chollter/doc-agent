package com.gcll.ticketagent.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportTest {

    @TempDir
    Path tempDir;

    @Test
    void reportCarriesPerCaseAssertionResults() {
        EvalAssertionResult assertion = new EvalAssertionResult(
                "replyType", false, "NEED_MORE_INFO", "TICKET_ANALYSIS_RESULT", "mismatch");
        EvalAuditStepResult auditStep = new EvalAuditStepResult(
                "TRIAGE_DECISION", "SUCCESS", false, null, 3,
                null, "type=NEED_MORE_INFO", null, "2026-07-21T00:00:00Z");
        EvalCaseResult caseResult = new EvalCaseResult(
                "insufficient-info", "core-regression", "run-1", false, List.of(assertion), List.of(auditStep));

        EvalReport report = new EvalReport(
                "eval/eval-cases.json",
                1,
                0,
                1,
                List.of(new EvalGroupReport("core-regression", 1, 0, 1)),
                List.of("insufficient-info failed"),
                Map.of("replyType", 1),
                List.of(caseResult),
                new EvalQualitySummary(true, 0.0, 0, List.of()),
                new EvalMetricsSummary(
                        1, 0, 0.0, 0, 1, 0, 0, 0, 0,
                        0, 0, 0, 0.0, 120, 120, 0, 0, 0, 0, 0, 0,
                        Map.of("core-regression", 1)
                ),
                null
        );

        assertThat(report.failureByAssertion()).containsEntry("replyType", 1);
        assertThat(report.caseResults()).singleElement().satisfies(result -> {
            assertThat(result.caseId()).isEqualTo("insufficient-info");
            assertThat(result.runId()).isEqualTo("run-1");
            assertThat(result.assertions()).singleElement()
                    .extracting(EvalAssertionResult::name)
                    .isEqualTo("replyType");
            assertThat(result.auditSteps()).singleElement()
                    .extracting(EvalAuditStepResult::stepName)
                    .isEqualTo("TRIAGE_DECISION");
        });
        assertThat(report.metricsSummary().casesByScenarioType()).containsEntry("core-regression", 1);
    }

    @Test
    void reportCanBeWrittenToFile() throws Exception {
        EvalReport report = new EvalReport(
                "eval/eval-cases.json#case:insufficient-info",
                1,
                1,
                0,
                List.of(new EvalGroupReport("core-regression", 1, 1, 0)),
                List.of(),
                Map.of(),
                List.of(new EvalCaseResult(
                        "insufficient-info",
                        "core-regression",
                        "run-1",
                        true,
                        List.of(),
                        List.of(new EvalAuditStepResult(
                                "INFO_GAP_ANALYSIS",
                                "SUCCESS",
                                true,
                                "SpringAI",
                                120,
                                null,
                                "ready=false",
                                null,
                                "2026-07-21T00:00:00Z"
                        ))
                )),
                new EvalQualitySummary(true, 0.0, 0, List.of()),
                EvalMetricsSummary.empty(),
                null
        );
        Path output = tempDir.resolve("eval-report.json");

        EvalReport written = new EvalReportFileWriter(new ObjectMapper(), tempDir.toString())
                .write(report, output.toString());

        assertThat(Files.exists(output)).isTrue();
        assertThat(written.outputFile()).isEqualTo(output.toAbsolutePath().toString());
        assertThat(Files.readString(output)).contains("\"outputFile\"");
        assertThat(Files.readString(output)).contains("\"auditSteps\"");
        assertThat(Files.readString(output)).contains("INFO_GAP_ANALYSIS");
    }
}
