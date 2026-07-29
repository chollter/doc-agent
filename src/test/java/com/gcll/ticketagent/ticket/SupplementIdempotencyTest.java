package com.gcll.ticketagent.ticket;

import com.gcll.ticketagent.agent.TicketAgentOrchestrator;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SupplementMessageRequest;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.infra.RunConcurrencyService;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupplementIdempotencyTest {

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate mockedRedisTemplate() {
        java.util.Map<String, String> store = new java.util.concurrent.ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            return store.putIfAbsent(key, value) == null;
        });
        when(ops.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    if (!keys.isEmpty()) {
                        store.remove(keys.get(0));
                    }
                    return 1L;
                });
        return redisTemplate;
    }

    @Test
    void duplicateSupplementReturnsSameResponseWithoutReExecuting() {
        AtomicInteger executionCount = new AtomicInteger(0);

        AgentRunRepository repository = new InMemoryAgentRunRepository(
                new AgentRun("run-1", "trace-1", "sess-1", "user-1", "initial content")
        );
        repository.findById("run-1").get().setStatus(AgentRunStatus.WAIT_USER_INPUT);

        TicketAgentOrchestrator orchestrator = new TicketAgentOrchestrator(null) {
            @Override
            public AgentRunResponse execute(AgentRun run, TicketDraft draft) {
                executionCount.incrementAndGet();
                return new AgentRunResponse(run.getId(), AgentRunStatus.FINAL,
                        ReplyType.TICKET_ANALYSIS_RESULT, "done", null, null, false);
            }
        };
        TicketApplicationService service = new TicketApplicationService(
                repository,
                orchestrator,
                new TransactionTemplate(new NoOpTransactionManager()),
                new RunConcurrencyService(mockedRedisTemplate()),
                new com.gcll.ticketagent.async.AsyncAgentRunProperties(),
                Optional.empty(),
                new TicketInputProcessor(8000),
                mock(AuditLogService.class),
                new AgentMetrics(new SimpleMeterRegistry())
        );

        SupplementMessageRequest request = new SupplementMessageRequest("supplement content", null);

        AgentRunResponse first = service.supplement("run-1", request);
        AgentRunResponse second = service.supplement("run-1", request);

        assertThat(first.runId()).isEqualTo("run-1");
        assertThat(second.runId()).isEqualTo("run-1");
        assertThat(executionCount.get()).isEqualTo(1);
    }

    @Test
    void differentSupplementContentExecutesBothTimes() {
        AtomicInteger executionCount = new AtomicInteger(0);

        AgentRunRepository repository = new InMemoryAgentRunRepository(
                new AgentRun("run-2", "trace-2", "sess-2", "user-2", "initial")
        );
        repository.findById("run-2").get().setStatus(AgentRunStatus.WAIT_USER_INPUT);

        TicketAgentOrchestrator orchestrator = new TicketAgentOrchestrator(null) {
            @Override
            public AgentRunResponse execute(AgentRun run, TicketDraft draft) {
                executionCount.incrementAndGet();
                return new AgentRunResponse(run.getId(), AgentRunStatus.FINAL,
                        ReplyType.TICKET_ANALYSIS_RESULT, "done", null, null, false);
            }
        };
        TicketApplicationService service = new TicketApplicationService(
                repository,
                orchestrator,
                new TransactionTemplate(new NoOpTransactionManager()),
                new RunConcurrencyService(mockedRedisTemplate()),
                new com.gcll.ticketagent.async.AsyncAgentRunProperties(),
                Optional.empty(),
                new TicketInputProcessor(8000),
                mock(AuditLogService.class),
                new AgentMetrics(new SimpleMeterRegistry())
        );

        service.supplement("run-2", new SupplementMessageRequest("first supplement", null));
        repository.findById("run-2").get().setStatus(AgentRunStatus.WAIT_USER_INPUT);
        service.supplement("run-2", new SupplementMessageRequest("different content", null));

        assertThat(executionCount.get()).isEqualTo(2);
    }

    private static class InMemoryAgentRunRepository implements AgentRunRepository {
        private final Map<String, AgentRun> store = new java.util.concurrent.ConcurrentHashMap<>();

        InMemoryAgentRunRepository(AgentRun... runs) {
            for (AgentRun run : runs) {
                store.put(run.getId(), run);
            }
        }

        @Override
        public AgentRun save(AgentRun run) {
            store.put(run.getId(), run);
            return run;
        }

        @Override
        public Optional<AgentRun> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<AgentRun> findByIdempotencyKey(String idempotencyKey) {
            return store.values().stream()
                    .filter(r -> idempotencyKey.equals(r.getIdempotencyKey()))
                    .findFirst();
        }

        @Override
        public List<AgentRun> findStuckRunningRuns(Instant updatedBefore) {
            return List.of();
        }

        @Override
        public Collection<AgentRun> findAll() {
            return List.copyOf(store.values());
        }
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
