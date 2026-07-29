package com.gcll.ticketagent.api;

import com.gcll.ticketagent.api.dto.EvalReportExportRequest;
import com.gcll.ticketagent.eval.EvalReport;
import com.gcll.ticketagent.eval.EvalReportFileWriter;
import com.gcll.ticketagent.eval.EvalRunner;
import com.gcll.ticketagent.eval.adversarial.AdversarialRedTeamService;
import com.gcll.ticketagent.eval.adversarial.RedTeamReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/evals")
public class EvalController {

    private final EvalRunner evalRunner;
    private final EvalReportFileWriter evalReportFileWriter;
    private final AdversarialRedTeamService adversarialRedTeamService;
    private final int defaultAdversarialCount;
    private final List<String> defaultStrategies;

    public EvalController(
            EvalRunner evalRunner,
            EvalReportFileWriter evalReportFileWriter,
            AdversarialRedTeamService adversarialRedTeamService,
            @Value("${opsmind.eval.adversarial.default-count:8}") int defaultAdversarialCount,
            @Value("${opsmind.eval.adversarial.strategies:OMIT_FIELD,CONTRADICTION,CROSS_DOMAIN,COLLOQUIAL,NON_INCIDENT,NOISE}") String strategies
    ) {
        this.evalRunner = evalRunner;
        this.evalReportFileWriter = evalReportFileWriter;
        this.adversarialRedTeamService = adversarialRedTeamService;
        this.defaultAdversarialCount = defaultAdversarialCount;
        this.defaultStrategies = Arrays.stream(strategies.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 运行 Eval 套件。默认只跑 golden 套件；{@code includeAdversarial=true} 时合并对抗 case。
     */
    @PostMapping("/run")
    public EvalReport run(
            @RequestParam(defaultValue = "full") String suite,
            @RequestParam(defaultValue = "false") boolean includeAdversarial
    ) {
        return evalRunner.run(suite, includeAdversarial);
    }

    @PostMapping("/run-case")
    public EvalReport runCase(
            @RequestParam String caseId
    ) {
        return evalRunner.runCase(caseId);
    }

    @PostMapping("/export")
    public EvalReport export(@RequestBody EvalReportExportRequest request) {
        if (request == null || request.report() == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        return evalReportFileWriter.write(request.report(), request.outputPath());
    }

    /**
     * 红队闭环：生成对抗工单 → 跑进 Agent → LLM 裁判判定 → 沉淀 fail 的输入为对抗 case。
     *
     * @param count     生成的对抗输入数量（默认取 {@code opsmind.eval.adversarial.default-count}）
     * @param strategies 攻击策略（可选，逗号分隔；缺省用配置默认）
     */
    @PostMapping("/adversarial/generate")
    public RedTeamReport generateAdversarial(
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) String strategies
    ) {
        int n = count == null ? defaultAdversarialCount : count;
        List<String> strategiesList = strategies == null || strategies.isBlank()
                ? defaultStrategies
                : Arrays.stream(strategies.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return adversarialRedTeamService.generate(n, strategiesList);
    }
}
