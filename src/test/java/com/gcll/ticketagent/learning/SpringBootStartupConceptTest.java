package com.gcll.ticketagent.learning;

import com.gcll.ticketagent.async.AsyncAgentRunConfig;
import com.gcll.ticketagent.async.AsyncAgentRunProperties;
import com.gcll.ticketagent.eval.judge.NoopEvalJudge;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Learning test: connect "Spring Boot startup principle" to this project.
 *
 * <p>Spring Boot startup is not just a memorized sequence. During context refresh,
 * it binds configuration properties and evaluates conditional beans. In this project
 * that decides whether async execution, eval judge, vector store, rerank, and MCP
 * related components are present.
 */
class SpringBootStartupConceptTest {

    @Test
    void configurationPropertiesAreBoundDuringStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(AsyncAgentRunConfig.class)
                .withPropertyValues(
                        "opsmind.async.enabled=true",
                        "opsmind.async.topic=learning.agent-run.execute",
                        "opsmind.async.stuck-running-timeout-minutes=3"
                )
                .run(context -> {
                    AsyncAgentRunProperties properties = context.getBean(AsyncAgentRunProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getTopic()).isEqualTo("learning.agent-run.execute");
                    assertThat(properties.getStuckRunningTimeoutMinutes()).isEqualTo(3);
                });
    }

    @Test
    void conditionalBeanIsCreatedWhenPropertyMatches() {
        new ApplicationContextRunner()
                .withUserConfiguration(NoopEvalJudge.class)
                .run(context -> assertThat(context).hasSingleBean(NoopEvalJudge.class));
    }

    @Test
    void conditionalBeanIsSkippedWhenPropertyDoesNotMatch() {
        new ApplicationContextRunner()
                .withUserConfiguration(NoopEvalJudge.class)
                .withPropertyValues("opsmind.eval.judge.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(NoopEvalJudge.class));
    }
}
