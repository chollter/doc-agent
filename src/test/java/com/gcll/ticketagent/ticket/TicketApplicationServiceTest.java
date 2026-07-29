package com.gcll.ticketagent.ticket;

import com.gcll.ticketagent.agent.TicketAgentOrchestrator;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.async.AsyncAgentRunProperties;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.infra.RunConcurrencyService;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketApplicationServiceTest {

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate mockedRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(ops.get(anyString())).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(String[].class)))
                .thenReturn(1L);
        return redisTemplate;
    }

    @Test
    void submitCommitsRunCreationBeforeExecutingAgentFlow() {
        CapturingAgentRunRepository agentRunRepository = new CapturingAgentRunRepository();
        AtomicBoolean orchestratorCalledInTransaction = new AtomicBoolean(true);
        AtomicReference<String> executedRunId = new AtomicReference<>();

        TicketAgentOrchestrator orchestrator = new TicketAgentOrchestrator(null) {
            @Override
            public AgentRunResponse execute(AgentRun run, TicketDraft draft) {
                orchestratorCalledInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                executedRunId.set(run.getId());
                return new AgentRunResponse(
                        run.getId(),
                        AgentRunStatus.FINAL,
                        ReplyType.TICKET_ANALYSIS_RESULT,
                        "ok",
                        null,
                        null,
                        false
                );
            }
        };
        TicketApplicationService service = new TicketApplicationService(
                agentRunRepository,
                orchestrator,
                new TransactionTemplate(new TestTransactionManager()),
                new RunConcurrencyService(mockedRedisTemplate(), null),
                new AsyncAgentRunProperties(),
                Optional.empty(),
                new TicketInputProcessor(8000),
                mock(AuditLogService.class),
                new AgentMetrics(new SimpleMeterRegistry())
        );

        AgentRunResponse response = service.submit(new SubmitAgentRunRequest(
                "sess-001",
                "user-001",
                "支付异常",
                "生产环境，支付回调 500",
                "WEB",
                null,
                null
        ));

        assertThat(response.runId()).isEqualTo(executedRunId.get());
        assertThat(agentRunRepository.savedInTransaction()).isTrue();
        assertThat(orchestratorCalledInTransaction.get()).isFalse();
    }

    private static class CapturingAgentRunRepository implements AgentRunRepository {

        private boolean savedInTransaction;

        @Override
        public AgentRun save(AgentRun run) {
            savedInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            return run;
        }

        @Override
        public Optional<AgentRun> findById(String id) {
            return Optional.empty();
        }

        @Override
        public Optional<AgentRun> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<AgentRun> findStuckRunningRuns(Instant updatedBefore) {
            return List.of();
        }

        @Override
        public Collection<AgentRun> findAll() {
            return java.util.List.of();
        }

        boolean savedInTransaction() {
            return savedInTransaction;
        }
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        }
    }
}
