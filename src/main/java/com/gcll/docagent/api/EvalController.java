package com.gcll.docagent.api;

import com.gcll.docagent.eval.EvalCase;
import com.gcll.docagent.eval.EvalReportStore;
import com.gcll.docagent.eval.EvalRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 评测端点——对 golden case 全集跑真实链路回归并返回报告（含轨迹指标）。
 * 同步执行（真实 Key 下每用例约 1-2 分钟），适合演示/CI 触发，不适合前端长连接轮询。
 * <p>P12c 起报告自动落盘（data/eval-reports/），并提供两次报告的用例级对比——
 * 改动前各跑一次、对比即回归信号，问题在到达用户之前被拦截。
 */
@RestController
@RequestMapping("/api/evals")
public class EvalController {

    private final EvalRunner evalRunner;
    private final EvalReportStore reportStore;

    public EvalController(EvalRunner evalRunner, EvalReportStore reportStore) {
        this.evalRunner = evalRunner;
        this.reportStore = reportStore;
    }

    /** 运行结构：{"reportId": "...", "report": {...}}——reportId 用于后续对比。 */
    public record RunResult(String reportId, EvalRunner.EvalReport report) {
    }

    @GetMapping("/cases")
    public List<EvalCase> cases() {
        return evalRunner.loadCases();
    }

    @PostMapping("/run")
    public RunResult run() {
        EvalRunner.EvalReport report = evalRunner.runAll();
        return new RunResult(reportStore.save(report), report);
    }

    /** 历史报告 ID 列表（旧→新）。 */
    @GetMapping("/reports")
    public List<String> reports() {
        return reportStore.list();
    }

    /** 两次报告对比：状态翻转的用例逐条列出（新增失败=回归信号）。 */
    @GetMapping("/compare")
    public EvalReportStore.CompareResult compare(@RequestParam String older, @RequestParam String newer)
            throws IOException {
        return reportStore.compare(older, newer);
    }
}
