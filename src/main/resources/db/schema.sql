-- 文档分析 Agent 核心表结构（H2/PostgreSQL 双兼容）
CREATE TABLE IF NOT EXISTS agent_run (
    id              VARCHAR(64)  PRIMARY KEY,
    trace_id        VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(128) NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(128),
    idempotency_key VARCHAR(256),
    version         BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(32)  NOT NULL,
    original_content TEXT,
    current_summary TEXT,
    last_error      TEXT,
    file_name       VARCHAR(256),
    file_type       VARCHAR(32),
    skill           VARCHAR(64),
    instruction     TEXT,
    section_count   INT,
    execution_mode  VARCHAR(32),
    result_json     TEXT,
    claimed_by      VARCHAR(128),
    tokens_used     BIGINT,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 老库迁移（新库建表已含这些列，ALTER 为 no-op）
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS file_name VARCHAR(256);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS file_type VARCHAR(32);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS skill VARCHAR(64);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS instruction TEXT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS section_count INT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(32);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS result_json TEXT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS tokens_used BIGINT;

CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run (session_id);
-- 队列扫描：按状态取待认领 run
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON agent_run (status, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_idempotency ON agent_run (idempotency_key);

CREATE TABLE IF NOT EXISTS agent_step (
    id              VARCHAR(64)  PRIMARY KEY,
    run_id          VARCHAR(64)  NOT NULL,
    parent_step_id  VARCHAR(64),
    step_name       VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    input_snapshot  TEXT,
    output_snapshot TEXT,
    llm_used        BOOLEAN      NOT NULL DEFAULT FALSE,
    tool_used       VARCHAR(64),
    cost_ms         BIGINT       NOT NULL DEFAULT 0,
    error_message   TEXT,
    span_id         VARCHAR(64),
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_step_run ON agent_step (run_id);

CREATE TABLE IF NOT EXISTS tool_execution_log (
    id              VARCHAR(64)  PRIMARY KEY,
    run_id          VARCHAR(64)  NOT NULL,
    step_name       VARCHAR(64)  NOT NULL,
    tool_type       VARCHAR(16)  NOT NULL,
    tool_name       VARCHAR(64)  NOT NULL,
    input_snapshot  TEXT,
    output_snapshot TEXT,
    duration_ms     BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL,
    error_message   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tool_exec_run ON tool_execution_log (run_id);

CREATE TABLE IF NOT EXISTS pending_action (
    id           VARCHAR(64)  PRIMARY KEY,
    run_id       VARCHAR(64)  NOT NULL,
    action_type  VARCHAR(32)  NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    payload      TEXT,
    reason       TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP,
    confirmed_by VARCHAR(64),
    target_team  VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_pending_action_run ON pending_action (run_id);

-- 自研循环检查点（每轮覆盖写入；run 完成后删除）
CREATE TABLE IF NOT EXISTS agent_checkpoint (
    run_id      VARCHAR(64)  PRIMARY KEY,
    round       INT          NOT NULL DEFAULT 0,
    state_json  TEXT         NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Spring AI ChatMemory 对话历史表（JDBC repository；与官方 schema-postgresql.sql 等价）
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(10)  NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY (conversation_id, "timestamp");
