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
    job_description TEXT,
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
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS job_description TEXT;
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

-- 追问轮次消息
CREATE TABLE IF NOT EXISTS agent_message (
    id         VARCHAR(64)  PRIMARY KEY,
    run_id     VARCHAR(64)  NOT NULL,
    turn       INT          NOT NULL DEFAULT 1,
    role       VARCHAR(16)  NOT NULL,
    content    TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_message_run ON agent_message (run_id);

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

-- LLM 交互日志：记录每次 LLM 调用的完整输入输出（优化证据链）
CREATE TABLE IF NOT EXISTS llm_interaction (
    id                VARCHAR(64)  PRIMARY KEY,
    run_id            VARCHAR(64)  NOT NULL,
    call_site         VARCHAR(32)  NOT NULL,
    model             VARCHAR(128),
    prompt_tokens     INT,
    completion_tokens INT,
    full_prompt       TEXT,
    full_response     TEXT,
    duration_ms       BIGINT,
    success           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_llm_interaction_run ON llm_interaction (run_id);

-- agent_run 扩展：版本标记 + 评分明细（优化过程可追溯）
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS optimization_note TEXT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS score_overall INT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS score_dimensions TEXT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS target_direction VARCHAR(128);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS persona VARCHAR(32);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_agent_run_content_hash ON agent_run (content_hash);
-- 版本迭代血缘：仅在用户显式确认基线时写入，系统绝不自动绑定；为空即首轮/无基线。
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS base_run_id VARCHAR(64);

-- 简历档案：按内容哈希去重的已解析简历（相同简历免重复上传，再次分析直接取库）
CREATE TABLE IF NOT EXISTS resume_profile (
    id           VARCHAR(64)  PRIMARY KEY,
    content_hash VARCHAR(64)  NOT NULL,
    file_name    VARCHAR(256),
    file_type    VARCHAR(32),
    char_count   INT,
    parsed_json  TEXT         NOT NULL,
    run_count    INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_resume_profile_hash ON resume_profile (content_hash);

-- 分析结论缓存：同简历 + 同分析输入（技能/指令/JD/方向/人群/prompt 版本）→ 复用历史结论
CREATE TABLE IF NOT EXISTS analysis_cache (
    id               VARCHAR(64)  PRIMARY KEY,
    cache_key        VARCHAR(128) NOT NULL,
    resume_id        VARCHAR(64)  NOT NULL,
    skill            VARCHAR(64)  NOT NULL,
    instruction      TEXT,
    job_description  TEXT,
    target_direction VARCHAR(128),
    persona          VARCHAR(32),
    prompt_version   VARCHAR(64),
    result_json      TEXT         NOT NULL,
    score_overall    INT,
    score_dimensions TEXT,
    source_run_id    VARCHAR(64),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_analysis_cache_key ON analysis_cache (cache_key);
CREATE INDEX IF NOT EXISTS idx_analysis_cache_resume ON analysis_cache (resume_id);
