package com.gcll.docagent.api;

import com.gcll.docagent.eval.EvalCase;
import com.gcll.docagent.eval.EvalRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测端点——对 golden case 全集跑真实链路回归并返回报告（含轨迹指标）。
 * 同步执行（真实 Key 下每用例约 1-2 分钟），适合演示/CI 触发，不适合前端长连接轮询。
 */
@RestController
@RequestMapping("/api/evals")
public class EvalController {

    private final EvalRunner evalRunner;

    public EvalController(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    @GetMapping("/cases")
    public List<EvalCase> cases() {
        return evalRunner.loadCases();
    }

    @PostMapping("/run")
    public EvalRunner.EvalReport run() {
        return evalRunner.runAll();
    }
}
