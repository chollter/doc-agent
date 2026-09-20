import request, { getErrorMessage } from './request';

/** 后端 base（dev 走 Vite 代理同源；static 集成后同源） */
const SSE_BASE = import.meta.env.PROD ? '' : 'http://localhost:8020';

export interface Citation {
  sectionId: string;
  quote: string;
}

// ---- 简历深度分析类型 ----

export interface SkillMatrix {
  languages: string[];
  frameworks: string[];
  infrastructure: string[];
  databases: string[];
  others: string[];
}

export interface WorkEntry {
  company: string;
  role: string | null;
  period: string | null;
  durationMonths: number;
  highlights: string[];
}

export interface EducationEntry {
  school: string;
  degree: string | null;
  major: string | null;
  year: string | null;
}

export interface ResumeProfile {
  name: string | null;
  yearsOfExperience: number;
  currentRole: string | null;
  workTimeline: WorkEntry[];
  skillMatrix: SkillMatrix;
  education: EducationEntry[];
}

export interface ActionableSuggestion {
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  target: string;
  sectionId: string | null;
  before: string;
  after: string;
  reason: string;
}

// ---- P12 漏斗式结论类型 ----

export interface RedFlag {
  type: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  message: string;
}

export interface PositioningCheck {
  anchored: boolean;
  currentAnchor: string | null;
  suggestedAnchor: string | null;
  comment: string | null;
}

export interface StrengthStats {
  entryCount: number;
  resultRate: number;
  strongResultRate: number;
  ownerRate: number;
  starCompleteRate: number;
  band: 'WEAK' | 'MIXED' | 'STRONG';
}

export interface Presentation {
  score: number;
  band: 'A' | 'B' | 'C' | 'D';
  issues: string[];
}

export interface LeverageCard {
  kind: 'STRENGTH' | 'RISK';
  point: string;
  sectionId: string | null;
  likelyQuestion: string | null;
  prepHint: string | null;
  defenseStrategy: string | null;
}

export interface FunnelVerdict {
  redFlags: RedFlag[] | null;
  matchMode: 'JD' | 'DIRECTION' | 'NONE';
  archetypeId: string | null;
  positioning: PositioningCheck | null;
  strength: StrengthStats | null;
  presentation: Presentation | null;
  leverageCards: LeverageCard[] | null;
  analysisDegraded: boolean;
  /** 定性评价（v6）；历史 run 无此字段 */
  evaluation?: Evaluation | null;
  /** 无 JD 时从简历自身证据推断的方向建议；tier 由后端按证据强度判定 */
  recommendedDirections?: DirectionRecommendation[] | null;
}

export interface DirectionRecommendation {
  direction: string;
  tier: 'BEST_FIT' | 'STRETCH';
  evidence: string[];
  sectionId: string | null;
}

/** 维度评语：档位由代码侧计算，评语归 LLM */
export interface EvaluationDimension {
  dimension: string;
  level?: 'STRONG' | 'MEDIUM' | 'WEAK' | 'UNKNOWN' | string;
  comment: string;
  evidence?: string[];
  issueType?: 'CAPABILITY_GAP' | 'EVIDENCE_INSUFFICIENT' | 'EXPRESSION_PROBLEM' | 'IRRELEVANT_TO_TARGET' | 'NONE' | string;
}

export interface Evaluation {
  overall: string | null;
  dimensions: EvaluationDimension[] | null;
  strengths: string[] | null;
  weaknesses: string[] | null;
}

// ---- 校准对照类型（53→75 的证据链） ----

export interface OptimizationHistoryItem {
  runId: string;
  fileName: string | null;
  skill: string | null;
  promptVersion: string | null;
  optimizationNote: string | null;
  scoreOverall: number | null;
  scoreDimensions: string | null;
  executionMode: string | null;
  tokensUsed: number | null;
  interactionCount: number;
  createdAt: string;
}

export interface CalibrationSnapshot {
  runId: string;
  promptVersion: string | null;
  optimizationNote: string | null;
  scoreOverall: number | null;
  strengthBand: string | null;
  presentationScore: number | null;
  presentationBand: string | null;
  matchBand: string | null;
  matchMode: string | null;
  highRedFlagCount: number;
  coverageMet: number;
  leverageCards: number;
  degraded: boolean;
}

export interface AngleDelta {
  angle: string;
  baseline: string;
  candidate: string;
  direction: 'IMPROVED' | 'REGRESSED' | 'UNCHANGED' | 'MISSING';
}

export interface OptimizationCompare {
  fileName: string;
  baseline: CalibrationSnapshot;
  candidate: CalibrationSnapshot;
  deltas: AngleDelta[];
  conclusion: string;
}

/** 采纳建议的结果：修改稿 + 失锚明细 + 变更节。 */
export interface AppliedRevision {
  revisedMarkdown: string;
  appliedCount: number;
  requestedCount: number;
  missingBefores: string[];
  changedSectionIds: string[];
}

export interface AnalysisResult {
  summary: string;
  keyPoints: string[];
  risks: string[];
  citations: Citation[];
  // 候选人画像
  profile?: ResumeProfile | null;
  // 可执行建议（before→after）
  actionableSuggestions?: ActionableSuggestion[] | null;
  // P12 漏斗式结论（主结果）
  funnelVerdict?: FunnelVerdict | null;
}

export interface RunStart {
  runId: string;
  status: string;
}

/** 简历档案条目（历史简历——免上传再分析的数据源） */
export interface ResumeItem {
  id: string;
  fileName: string | null;
  fileType: string | null;
  charCount: number | null;
  runCount: number | null;
  createdAt: string;
  lastUsedAt: string;
}

export interface RunSummary {
  runId: string;
  fileName: string;
  skill: string | null;
  instruction: string;
  status: string;
  executionMode: string | null;
  createdAt: string;
}

export interface HumanActionDto {
  id: string;
  runId: string;
  actionType: string;
  status: string;
  payload: string;
  reason: string;
  createdAt: string;
}

export interface RunDetail extends RunSummary {
  finishedAt: string | null;
  summary: string | null;
  result: AnalysisResult | null;
  lastError: string | null;
  pending: HumanActionDto[] | null;
}

export interface DocSection {
  id: string;
  heading: string | null;
  page: number | null;
  charCount: number;
  text: string;
}

export interface DocumentView {
  runId: string;
  fileName: string;
  fileType: string;
  sectionCount: number;
  sections: DocSection[];
}

/** 追问消息 DTO */
export interface MessageDto {
  turn: number;
  role: string;
  content: string;
  createdAt: string;
}

export interface AuditStep {
  id: string;
  parentStepId: string | null;
  stepName: string;
  status: string;
  inputSnapshot: string | null;
  outputSnapshot: string | null;
  llmUsed: boolean;
  toolUsed: string | null;
  costMs: number;
  errorMessage: string | null;
  createdAt: string;
}

// ---- 链路诊断类型（单次运行的阶段级状态） ----

export interface PipelineStage {
  stage: string;
  status: string;
  detail: string | null;
  costMs: number | null;
  error: string | null;
}

export interface LlmCallGroup {
  callSite: string;
  total: number;
  failures: number;
}

export interface RunPipeline {
  runId: string;
  runStatus: string;
  executionMode: string | null;
  analysisDegraded: boolean;
  lastError: string | null;
  stages: PipelineStage[];
  llmCalls: LlmCallGroup[];
}

/** SSE 步骤事件（实时 + 回放同构） */
export interface StepEvent {
  runId: string;
  stepId: string;
  stepName: string;
  status: string;
  message: string;
  timestamp: number;
}

/** SSE token 事件（LLM 流式增量，2026-09-18） */
export interface TokenEvent {
  runId: string;
  stepId: string;
  delta: string;
  timestamp: number;
}

export const analysisApi = {
  /** 提交分析：file 与 opts.resumeId 二选一（免上传用 resumeId）；forceRefresh 跳过结论缓存强制重跑 */
  submit(
    file: File | null,
    instruction: string,
    skill?: string,
    jobDescription?: string,
    targetDirection?: string,
    persona?: string,
    promptVersion?: string,
    optimizationNote?: string,
    opts?: { resumeId?: string; forceRefresh?: boolean },
  ): Promise<RunStart> {
    const form = new FormData();
    if (file) form.append('file', file);
    form.append('instruction', instruction);
    if (skill) form.append('skill', skill);
    if (jobDescription) form.append('jobDescription', jobDescription);
    if (targetDirection) form.append('targetDirection', targetDirection);
    if (persona) form.append('persona', persona);
    if (promptVersion) form.append('promptVersion', promptVersion);
    if (optimizationNote) form.append('optimizationNote', optimizationNote);
    if (opts?.resumeId) form.append('resumeId', opts.resumeId);
    if (opts?.forceRefresh) form.append('forceRefresh', 'true');
    return request.upload('/api/analysis/runs', form);
  },

  /** 简历档案列表（最近使用在前） */
  listResumes(): Promise<ResumeItem[]> {
    return request.get('/api/analysis/resumes');
  },

  confirmAction(id: string): Promise<void> {
    return request.post(`/api/human/actions/${id}/confirm`);
  },

  rejectAction(id: string): Promise<void> {
    return request.post(`/api/human/actions/${id}/reject`);
  },

  getRun(runId: string): Promise<RunDetail> {
    return request.get(`/api/analysis/runs/${runId}`);
  },

  listRuns(): Promise<RunSummary[]> {
    return request.get('/api/analysis/runs');
  },

  getDocument(runId: string): Promise<DocumentView> {
    return request.get(`/api/analysis/runs/${runId}/document`);
  },

  getAudit(runId: string): Promise<AuditStep[]> {
    return request.get(`/api/audit/agent-runs/${runId}`);
  },

  /** 单次运行链路诊断：阶段状态条 + LLM 调用聚合 */
  getPipeline(runId: string): Promise<RunPipeline> {
    return request.get(`/api/analysis/runs/${runId}/pipeline`);
  },

  /** 采纳建议（before→after 替换），返回修改稿 */
  applySuggestions(runId: string, indices: number[]): Promise<AppliedRevision> {
    return request.post(`/api/analysis/runs/${runId}/apply-suggestions`, { indices });
  },

  /** 优化历史（校准记录链） */
  getOptimizationHistory(): Promise<OptimizationHistoryItem[]> {
    return request.get('/api/analysis/optimization-history');
  },

  /** 校准对照：同文件两个版本（或两个 runId）的分角度 diff */
  compareOptimization(params: {
    fileName?: string;
    baselineVersion?: string;
    candidateVersion?: string;
    runA?: string;
    runB?: string;
  }): Promise<OptimizationCompare> {
    const qs = new URLSearchParams(
      Object.fromEntries(Object.entries(params).filter(([, v]) => v != null && v !== '')) as Record<string, string>,
    );
    return request.get(`/api/analysis/optimization-compare?${qs.toString()}`);
  },

  /** 追问（面试模拟核心通道） */
  followUp(runId: string, message: string): Promise<RunStart> {
    return request.post(`/api/analysis/runs/${runId}/messages`, { message });
  },

  /** 消息历史（面试对话回放） */
  getMessages(runId: string): Promise<MessageDto[]> {
    return request.get(`/api/analysis/runs/${runId}/messages`);
  },
};

/**
 * 订阅 run 的步骤流（EventSource）。
 * 返回关闭函数。onEvent 收到的是"合并视图"——同一步骤的 RUNNING 与终态按 stepId 更新。
 * onToken（可选）接收 LLM 流式增量（DIRECT_LLM 实时生成）。
 */
export function subscribeSteps(
  runId: string,
  onEvent: (event: StepEvent) => void,
  onError?: (message: string) => void,
  onToken?: (event: TokenEvent) => void,
): () => void {
  const source = new EventSource(`${SSE_BASE}/api/analysis/runs/${runId}/stream`);
  source.addEventListener('step', (e) => {
    try {
      onEvent(JSON.parse((e as MessageEvent).data) as StepEvent);
    } catch {
      // 忽略畸形事件
    }
  });
  if (onToken) {
    source.addEventListener('token', (e) => {
      try {
        onToken(JSON.parse((e as MessageEvent).data) as TokenEvent);
      } catch {
        // 忽略畸形事件
      }
    });
  }
  source.onerror = () => {
    // EventSource 断开会自动重连；这里只上报，由调用方决定是否轮询兜底
    onError?.('SSE 连接中断，将按轮询兜底');
  };
  return () => source.close();
}

export { getErrorMessage };
