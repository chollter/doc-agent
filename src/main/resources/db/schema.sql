CREATE TABLE IF NOT EXISTS agent_run (
    id              VARCHAR(64)  PRIMARY KEY,
    trace_id        VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(128) NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(128),
    idempotency_key VARCHAR(256),
    version         BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(32)  NOT NULL,
    original_content TEXT        NOT NULL,
    current_summary TEXT,
    issue_type      VARCHAR(32),
    priority        VARCHAR(8),
    gap_analysis_json   TEXT,
    agent_plan_json     TEXT,
    tool_selection_json TEXT,
    last_error      TEXT,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run (session_id);
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

CREATE TABLE IF NOT EXISTS knowledge_document (
    id           VARCHAR(64)  PRIMARY KEY,
    source_type  VARCHAR(32)  NOT NULL,
    source_id    VARCHAR(128) NOT NULL,
    title        VARCHAR(256) NOT NULL,
    content      TEXT         NOT NULL,
    system_name  VARCHAR(128),
    module_name  VARCHAR(128),
    tags         TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_knowledge_system_module ON knowledge_document (system_name, module_name);

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
    target_team   VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_pending_action_run ON pending_action (run_id);

CREATE TABLE IF NOT EXISTS ops_log_sample (
    id              VARCHAR(64)  PRIMARY KEY,
    system_name     VARCHAR(128) NOT NULL,
    service_name    VARCHAR(128) NOT NULL,
    module_name     VARCHAR(128),
    environment     VARCHAR(32)  NOT NULL,
    level           VARCHAR(16)  NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL,
    trace_id        VARCHAR(128),
    message         TEXT         NOT NULL,
    tags            TEXT
);

CREATE INDEX IF NOT EXISTS idx_ops_log_lookup
    ON ops_log_sample (system_name, service_name, environment, occurred_at);

CREATE TABLE IF NOT EXISTS ops_metric_sample (
    id              VARCHAR(64)  PRIMARY KEY,
    system_name     VARCHAR(128) NOT NULL,
    service_name    VARCHAR(128) NOT NULL,
    metric_name     VARCHAR(128) NOT NULL,
    environment     VARCHAR(32)  NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL,
    metric_value    DOUBLE PRECISION NOT NULL,
    unit            VARCHAR(32),
    labels          TEXT,
    status          VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_ops_metric_lookup
    ON ops_metric_sample (system_name, service_name, metric_name, environment, occurred_at);

-- 语义缓存：对相似 query 复用 LLM / RAG 的历史结果，省 Token + 加速重复工单。
CREATE TABLE IF NOT EXISTS semantic_cache (
    id              VARCHAR(64)  PRIMARY KEY,
    cache_type      VARCHAR(16)  NOT NULL,    -- LLM / RAG
    call_name       VARCHAR(64),              -- LLM 缓存对应的 callName
    query_text      TEXT         NOT NULL,    -- 原始查询文本（用于展示/调试）
    query_hash      VARCHAR(64)  NOT NULL,    -- query 的 SHA-256（精确匹配快速路径）
    response_text   TEXT         NOT NULL,    -- 缓存的响应内容
    metadata_json   TEXT,                     -- 附加元数据（RAG 过滤条件 / LLM model 等）
    hit_count       INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP               -- TTL 过期时间，null 表示不过期
);

CREATE INDEX IF NOT EXISTS idx_semantic_cache_type_hash ON semantic_cache (cache_type, query_hash);
CREATE INDEX IF NOT EXISTS idx_semantic_cache_expires ON semantic_cache (expires_at);
