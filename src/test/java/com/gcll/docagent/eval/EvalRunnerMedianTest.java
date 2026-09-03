package com.gcll.docagent.eval;

import com.gcll.docagent.domain.AgentRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校准用例的中位代表选择——LLM 单次判断的方差不进入断言：
 * 三次运行 [53, 75, 40] 应取 53（中位），而非最好或最差的一次。
 */
class EvalRunnerMedianTest {

    private static AgentRun withOverall(int score) {
        AgentRun run = new AgentRun("r" + score, "t", "s", "u", "c");
        run.setScoreOverall(score);
        return run;
    }

    @Test
    void shouldPickMedianOfOddRuns() {
        List<AgentRun> runs = List.of(withOverall(53), withOverall(75), withOverall(40));

        assertThat(EvalRunner.pickMedianRun(runs).getScoreOverall()).isEqualTo(53);
    }

    @Test
    void shouldPickUpperMedianOfEvenRuns() {
        List<AgentRun> runs = List.of(withOverall(20), withOverall(80), withOverall(50), withOverall(60));

        assertThat(EvalRunner.pickMedianRun(runs).getScoreOverall()).isEqualTo(60);
    }

    @Test
    void shouldTreatNullOverallAsZero() {
        AgentRun nullRun = new AgentRun("rn", "t", "s", "u", "c");

        assertThat(EvalRunner.pickMedianRun(List.of(nullRun, withOverall(10)).stream()
                        .sorted(java.util.Comparator.comparingInt(r -> r.getScoreOverall() == null ? 0 : r.getScoreOverall()))
                        .toList()).getScoreOverall()).isEqualTo(10);
    }

    @Test
    void singleRunShouldReturnItself() {
        assertThat(EvalRunner.pickMedianRun(List.of(withOverall(75))).getScoreOverall()).isEqualTo(75);
    }
}
