package com.gcll.ticketagent.understanding.completeness;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.triage.TriageDecision;
import com.gcll.ticketagent.governance.triage.TriageDecisionService;
import com.gcll.ticketagent.understanding.followup.FollowUpQuestionService;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CompletenessDecisionService {

    private final SchemaCompletenessChecker schemaCompletenessChecker;
    private final FollowUpQuestionService followUpQuestionService;
    private final TriageDecisionService triageDecisionService;

    public CompletenessDecisionService(
            SchemaCompletenessChecker schemaCompletenessChecker,
            FollowUpQuestionService followUpQuestionService,
            TriageDecisionService triageDecisionService
    ) {
        this.schemaCompletenessChecker = schemaCompletenessChecker;
        this.followUpQuestionService = followUpQuestionService;
        this.triageDecisionService = triageDecisionService;
    }

    public CompletenessDecision decide(String userContent, TicketExtractResult extract, InfoGapAnalysis gap, String runId) {
        SchemaCompletenessResult schema = schemaCompletenessChecker.check(extract);
        // 始终用可变 ArrayList：后续要把 gap.schemaMissing 合并进来（add），
        // 用 List.of()（不可变）会在 add 时抛 UnsupportedOperationException。
        // 这对 schema.complete()=true 的场景（如 CONSULT 类型）尤其关键。
        List<String> missingSchema = new ArrayList<>(schema.missingFields());

        List<String> schemaMissingFromGap = gap.schemaMissing() == null ? List.of() : gap.schemaMissing();
        for (String field : schemaMissingFromGap) {
            if (!missingSchema.contains(field)) {
                missingSchema.add(field);
            }
        }

        TriageDecision triage = triageDecisionService.decide(userContent, extract, gap, missingSchema);
        boolean needFollowUp = triage.needFollowUp();
        boolean canProceed = triage.canAnalyze();

        List<String> semanticGaps = gap.semanticGaps() == null ? List.of() : gap.semanticGaps();
        List<String> questions = needFollowUp
                ? followUpQuestionService.generate(
                userContent,
                extract,
                missingSchema,
                semanticGaps,
                gap.suggestedQuestions(),
                runId
        )
                : List.of();

        if (needFollowUp && questions.isEmpty()) {
            questions = List.of("请补充系统、环境、接口、错误信息和影响范围，方便准确分派。");
        }

        String reason = triage.reason();

        return new CompletenessDecision(
                canProceed,
                needFollowUp,
                triage.type().name(),
                List.copyOf(missingSchema),
                List.copyOf(semanticGaps),
                questions,
                reason
        );
    }

}
