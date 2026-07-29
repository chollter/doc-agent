## 项目定位

OpsMind Agent 是一个面向技术支持工单的 AI 分诊与故障排查样板。它不是“大而全”的工单平台，而是聚焦几个高价值问题：工单描述经常信息不足，排查需要结合日志、历史案例和处理流程，LLM 输出需要来源约束、审计追踪、失败降级和人工确认。

核心闭环：

```text
工单输入 -> 结构化抽取 -> 信息缺口判断 -> Agent 规划 -> 工具 / RAG 取证
        -> 根因分析 -> 优先级评估 -> 团队路由 -> 人工确认 -> 审计与评测
```

项目重点展示：

- Agent 编排：把抽取、追问、规划、查证、根因、路由、建议拆成可审计步骤。
- RAG 来源约束：建议和根因尽量绑定标准处理流程、排查手册、历史故障等知识来源。
- 工具治理：LLM 负责选择和解释，真实工具调用由后端统一执行、超时、记录和降级。
- HITL 风控：P1 / 高风险 / 低置信度结果进入人工确认，不让模型直接闭环高风险动作。
- Eval 与观测：支持 Golden Case 回归评测，并按 run 查看 LLM 调用次数、耗时、失败和 fallback。

推荐先看：

- `docs/demo-guide.md`：本地演示顺序和讲解口径。
- `docs/project-design-tradeoffs.md`：架构设计、取舍和边界。
- `docs/demo-cases.md`：固定演示用例。
- `docs/interview-deep-dive-playbook.md`：面试连续追问准备。

## 快速启动

> 默认向量后端为 **PgVector**（复用主库 PostgreSQL，无需额外容器）。如需切换 Milvus，
> 见下文「向量后端切换」。

### 第 -1 步：构建 MCP server 子进程（仅首次/代码变更后，手动执行）

主应用启动时会通过 STDIO 拉起一个独立的 MCP server 子进程（`ops-mcp-server`）来执行
`query_logs` 等受控工具。该模块是**仓库内的独立工程**（根 pom 不含 `<modules>`，
根目录 `mvn package` 不会打包它），需要单独构建一次：

```bash
mvn -f ops-mcp-server/pom.xml clean package -DskipTests
```

产出 `ops-mcp-server/target/ops-mcp-server-1.0.0-SNAPSHOT.jar`。`application.yml` 的
`spring.ai.mcp.client.stdio.connections.ops-mcp-server` 默认指向这个路径（相对于仓库根）。

> 如果只想跑主应用、不需要 MCP 工具查真实数据，可关闭子进程拉起（MCP 工具会降级返回"无证据"）：
> `set MCP_ENABLED=false` 或 `--spring.ai.mcp.client.enabled=false`。否则缺失 jar 会导致
> `Unable to access jarfile` 启动报错。

### 第 0 步：准备数据库（仅首次，手动执行一次）

需要一个运行中的 PostgreSQL（≥ 12 建议 15+）实例。用具备 superuser/createdb 权限的账号
连接到默认库，执行建库脚本：

```bash
psql -U postgres -h localhost -d postgres -f src/main/resources/db/init-database.sql
```

该脚本会：创建库 `tech_support_agent`、确认应用账号、在库内安装 pgvector 扩展（vector /
hstore / uuid-ossp）并授权。库名/账号默认值与 `application.yml` 一致（`tech_support_agent`
/ `postgres` / `postgres`），可用环境变量覆盖（见下文「配置」）。

### 第 1 步：启动应用

```bash
mvn spring-boot:run
```

浏览器打开：

```text
http://localhost:8020/
```

如果没有配置 `LLM_API_KEY`，系统会走规则/模板降级，核心 Demo 仍可运行。

### 脚本执行顺序总览

| 顺序 | 脚本 / 机制 | 何时执行 | 作用 |
|---|---|---|---|
| **0** | `db/init-database.sql` | **手动**，仅首次（或环境重建时） | 建库 + 应用账号 + 安装 pgvector 扩展 |
| **1** | `db/schema.sql` | 应用每次启动自动 | 建业务表（`agent_run` / `agent_step` / `knowledge_document` / `tool_execution_log` / `pending_action` / `ops_log_sample` / `ops_metric_sample`） |
| **2** | `db/data-knowledge.sql` | 应用每次启动自动 | 灌知识库种子数据（标准处理流程 / 排查手册 / 历史故障等） |
| **3** | `PgVectorStoreConfig`（`initializeSchema=true`） | 应用首次启动自动 | 建 `vector_store` 表 + HNSW 索引（依赖第 0 步已装扩展） |
| —（手动）| `db/pgvector-init.sql` | 仅当选择「预建表」路径时手动跑 | 预建 `vector_store` 表（此时需把 `PgVectorStoreConfig` 的 `initializeSchema` 改为 `false`） |

> 顺序 1/2 由 `application.yml` 的 `spring.sql.init.mode: always` 驱动；顺序 3 由
> Spring AI 的 `PgVectorStore` 在应用启动时执行。只有第 0 步和（可选的）pgvector-init
> 需要人手执行，其余全自动。

### 配置

默认连接 `localhost:5432/tech_support_agent`，账号 `postgres/postgres`。通过环境变量覆盖：

```bash
set PG_HOST=localhost
set PG_PORT=5432
set PG_DATABASE=tech_support_agent
set PG_USER=postgres
set PG_PASSWORD=postgres
set LLM_API_KEY=你的DashScope密钥
set LLM_MODEL=qwen-plus
```

### 向量后端切换（可选）

默认 PgVector（零额外容器）。如需 Milvus：

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=milvus
```

`milvus` profile 会激活 `MilvusVectorStoreConfig`（`@Profile("milvus")`）覆盖默认的 PgVector Bean。

## 常用接口

信息不足追问：

```bash
curl.exe -X POST http://localhost:8020/api/tickets/agent-runs ^
  -H "Content-Type: application/json" ^
  -d "{\"sessionId\":\"sess-001\",\"userId\":\"u-1001\",\"content\":\"接口报错了\",\"source\":\"WEB\"}"
```

完整故障分析：

```bash
curl.exe -X POST http://localhost:8020/api/tickets/agent-runs ^
  -H "Content-Type: application/json" ^
  -d "{\"sessionId\":\"sess-002\",\"userId\":\"u-1001\",\"content\":\"生产环境 payment-service Pod OOMKilled，从上午 10 点开始，多个用户支付失败，内存使用从 200Mi 飙升到 512Mi 后被 kill\",\"source\":\"WEB\"}"
```

SSE 步骤流：

```bash
curl.exe -N http://localhost:8020/api/tickets/agent-runs/{runId}/stream
```

审计：

```bash
curl.exe http://localhost:8020/api/audit/agent-runs/{runId}
```

LLM 调用统计：

```bash
curl.exe http://localhost:8020/api/audit/agent-runs/{runId}/llm-stats
```

运行 Eval：

```bash
curl.exe -X POST http://localhost:8020/api/evals/run
```

## 异步模式

默认接口仍同步返回完整分析结果，便于本地演示。开启 Kafka 异步执行：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=milvus -Dspring-boot.run.arguments="--opsmind.async.enabled=true"
```

异步模式下提交接口先返回 runId，后台通过 Kafka 消费 run execution event 推进 Agent 流程。


## 目录

```text
agent/           Agent 编排、Planner、SSE
execution/       L3 ToolSelector
ticket/          工单 API
extract/         L1 结构化抽取
understanding/   L1/L2 缺口分析、完整度决策、场景化追问
knowledge/       RAG 检索
execution/       L3 Tool 选择、Evidence（重构目标）
tool/            Function + MCP Tool 抽象
evidence/        Evidence 收集（重构中迁至 execution/）
analysis/        L4 根因分析
governance/      L5 优先级、路由、HITL（重构目标）
priority/        优先级规则（重构中迁至 governance/）
routing/         团队路由（重构中迁至 governance/）
suggestion/      L4 处理建议 + 操作步骤
human/           人工确认（重构中迁至 governance/）
persistence/     MyBatis-Plus
llm/             Spring AI 网关
eval/            Golden Case 评测
docs/            架构与重构文档
```
