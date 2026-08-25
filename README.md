# DocAgent — 文档分析 Agent

上传一份文档（PDF / DOCX / MD / TXT），输入你的要求，Agent 以 **ReAct 工具循环**自主阅读文档，实时展示每一步思考与工具调用，最终输出**引用可溯源**的结构化分析报告（摘要 / 关键内容 / 风险 / 建议）。

> 面试演示友好：一条命令启动、零外部中间件、LLM 不可用时三级降级保证演示不断线、历史 run 可完整回放审计。

## 90 秒演示剧本

1. `mvn spring-boot:run` 启动（约 3 秒），打开 http://localhost:8020
2. 点击「示例简历」一键加载 PDF（或拖入任意文档）
3. 点击「开始分析」→ 中栏实时滚动 Agent 时间线：**解析文档 → 获取大纲 → 逐节阅读（工具调用，可展开参数与返回）→ 引用校验 → 生成报告**
4. 右栏报告：摘要 / 关键点 / 风险 / 建议；点击引用 chip → 右侧文档面板**定位高亮原文出处**
5. 切到「历史记录」→ 点击任意 run → 回放完整执行链路树（17 步级 trace，含耗时 / 工具 / LLM 徽标）
6. （加分）不配置 API Key 重启 → executionMode 变为 FALLBACK，链路与报告依然完整——降级链路兜底

## 架构

```mermaid
flowchart LR
    subgraph Frontend["React 工作台"]
        UI[分析工作台 / 历史回放]
    end
    subgraph API["Spring Boot 3"]
        REST[POST /api/analysis/runs<br/>异步 + SSE]
        SSE[步骤流·回放]
    end
    subgraph Engine["分析引擎"]
        PARSE[PARSE 解析分节]
        REACT[ReAct 工具循环<br/>LangChain4j AiService]
        DIRECT[直连 LLM 降级]
        RULE[规则摘要兜底]
        VERIFY[引用校验]
    end
    subgraph Platform["平台层（可复用基建）"]
        TOOLS[ToolRegistry + ToolRuntime<br/>风险分级 READ/WRITE/DANGER]
        TRACE[TraceRecorder<br/>步级落库 + SSE + OTel]
        RES[Resilience4j<br/>重试/熔断/超时/限流]
        LLM[LlmGateway<br/>多模型路由 + 上下文窗口]
    end
    DB[(H2 / PostgreSQL)]

    UI --> REST --> PARSE --> REACT
    REACT -- 失败降级 --> DIRECT -- 失败降级 --> RULE
    REACT --> VERIFY --> DB
    REACT & DIRECT -.->. TOOLS & LLM
    TRACE --> SSE --> UI
    TRACE --> DB
    TOOLS & LLM --> RES
```

**一次 run 的执行链**：`PARSE → REACT_ANALYZE（get_document_outline / read_section / search_document 自主迭代，最多 8 轮）→ [降级] DIRECT_LLM → [兜底] RULE_FALLBACK → CITATION_VERIFY → REPORT`，每步落库 `agent_step` 并推送 SSE。

## 核心特性

| 特性 | 说明 |
|---|---|
| 真·ReAct 循环 | LangChain4j AiService 驱动 Thought→Action→Observation，LLM 自主决定读哪些节、搜什么关键词 |
| 工具治理 | 工具统一注册 ToolRegistry（READ/WRITE/DANGER 风险分级），经 ToolRuntime 执行：Resilience4j 重试/熔断/超时 + tool_execution_log 审计 |
| 三级降级 | ReAct 失败 → 直连 LLM（单次调用，按模型窗口截断）→ 规则摘要。无 API Key 也能完整演示 |
| 引用可溯源 | LLM 输出必须携带 `{sectionId, quote}` 引用；CITATION_VERIFY 步剔除指向不存在节的编造引用、错位引用自动重挂 |
| 垂直 Skill | SkillDefinition 注册表：同一执行引擎承载多个技能（文档分析 / 简历审查），技能只差提示词+工具集+默认指令 |
| 人工确认闭环（HITL） | DANGER 级 export_report 工具被 ToolConfirmGate 拦截：run 进入 WAIT_HUMAN_CONFIRM、前端弹确认卡，人工确认后才写盘；拒绝/超时自动跳过，全程在 trace 可见 |
| 全链路 Trace | 每步（含 ReAct 每轮 LLM 响应与每次工具调用）写 agent_step 树形表 + SSE 实时推送 + OTel span（双写已实现，默认采样 0，接 Collector 可开） |
| 多格式解析 | PDF（PDFBox，扫描件明确报错）/ DOCX（POI，标题样式分节）/ MD（标题分节）/ TXT（空行聚合），统一分节视图 |
| 一键演示 | 默认 H2 文件库 + 内嵌前端构建产物，克隆后 `mvn spring-boot:run` 即跑（仅需 `LLM_API_KEY` 可选） |

## 快速开始

环境要求：JDK 21+（Node 仅前端开发时需要）。

```bash
# 1.（可选）配置 DashScope API Key——不配也能跑（规则降级模式）
export LLM_API_KEY=sk-xxx        # Windows: set LLM_API_KEY=sk-xxx

# 2. 启动（内嵌前端 + H2，无任何外部依赖）
mvn spring-boot:run

# 3. 打开
http://localhost:8020
```

生产/团队环境切 PostgreSQL：`--spring.profiles.active=pg`（连接参数见 `application-pg.yml`）。

### 前端开发模式

```bash
cd frontend
pnpm install
pnpm dev          # Vite 5173，代理 /api 到 8020，热更新
pnpm build        # 产物直出 ../src/main/resources/static
```

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/analysis/runs` | multipart（file + instruction），202 返回 runId，异步执行 |
| GET | `/api/analysis/runs` | 历史列表 |
| GET | `/api/analysis/runs/{id}` | 详情（状态 + 结构化结果） |
| GET | `/api/analysis/runs/{id}/stream` | SSE 步骤流（连接即回放已落库步骤，支持刷新） |
| GET | `/api/analysis/runs/{id}/document` | 文档分节视图（引用定位） |
| GET | `/api/audit/agent-runs/{id}` | 步骤树审计（parentStepId / 耗时 / 工具 / LLM） |
| GET | `/api/human/pending` | 待人工确认动作（可按 runId 过滤） |
| POST | `/api/human/actions/{id}/confirm`、`/reject` | 确认/拒绝 DANGER 工具操作 |

## 项目结构

```
src/main/java/com/gcll/docagent/
├── analysis/      # 技能注册表 + 分析编排：run 生命周期、三级降级、引用校验
├── parsing/       # 四格式解析器 + 统一分节视图（DocumentParser SPI）
├── langchain4j/   # ReAct 装配：DocumentAnalysisAssistant + 工具提供者 + 步级监听
├── tool/          # 工具体系：ToolGateway SPI、注册表、风险分级、DANGER 门控
│   └── document/  # 三个文档工具：outline / read_section / search
├── platform/tool/ # ToolRuntime：批量执行 + 治理包裹 + 审计落库
├── llm/           # LlmGateway：多模型路由、上下文窗口管理、摘要式记忆
├── resilience/    # Resilience4j 网关：按调用类型的重试/熔断/超时/限流
├── observability/ # TraceRecorder：步级双写（业务表 + OTel）
└── api/           # REST + SSE 控制器
frontend/          # React 18 + TS + Tailwind 工作台（构建产物进 static）
sample-docs/       # 演示示例：简历 PDF / 需求 DOCX / 技术方案 MD
```

## 面试讲解要点

1. **为什么用双框架（Spring AI + LangChain4j）**：流程化调用（降级路径、token 统计）走 Spring AI ChatClient 生态；ReAct 自主工具循环需要 AiService 的类型安全工具协议——同一 DashScope 模型经两种协议接入，职责分离互不干扰。
2. **工具调用如何做治理**：LLM 只"选择"，执行统一走 ToolRuntime——注册表查找 → Resilience4j 包裹（副作用工具不重试）→ tool_execution_log 落库 → 指标打点。READ/WRITE/DANGER 三级风险分级：DANGER 级 export_report 被 ToolConfirmGate 拦截，run 转 WAIT_HUMAN_CONFIRM，前端确认卡人工放行后才写盘（可现场演示）。

6. **为什么做 Skill 抽象**：Runtime 与业务解耦的实证——文档分析和简历审查两个技能共享同一执行引擎/降级链/trace/前端，新增技能只是在 SkillRegistry 注册提示词+工具集。
3. **降级链设计**：三级降级各对应一类故障（循环失控 / LLM 网关故障 / 无 Key），executionMode 字段让降级对用户可见、对面试官可讲。
4. **引用防幻觉**：结构化引用 + 校验步（存在性检查 + 引文重挂），报告每条结论可点击跳回原文。
5. **工程细节**：MyBatis-Plus 乐观锁版本号在 upsert 时的同步问题、SSE"先回放后实时"解决连接竞态、H2/PG 双兼容 schema、`system-base` prompt 双路径统一。

## 测试

68 个测试：解析层（四格式，PDF/DOCX fixture 测试内生成）、文档工具、工具运行时、治理网关、以及覆盖「LLM 正常 / LLM 失败降级 / 非法文件拒收」三场景的端到端集成测试（@MockitoBean 替换 LLM 网关）。

```bash
mvn test
```
