import request, { getErrorMessage } from './request';

/** 后端 base（dev 走 Vite 代理同源；static 集成后同源） */
const SSE_BASE = import.meta.env.PROD ? '' : 'http://localhost:8020';

export interface Citation {
  sectionId: string;
  quote: string;
}

export interface AnalysisResult {
  summary: string;
  keyPoints: string[];
  risks: string[];
  suggestions: string[];
  citations: Citation[];
}

export interface RunStart {
  runId: string;
  status: string;
}

export interface RunSummary {
  runId: string;
  fileName: string;
  fileType: string;
  skill: string | null;
  instruction: string;
  status: string;
  executionMode: string | null;
  sectionCount: number | null;
  createdAt: string;
  finishedAt: string | null;
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

/** SSE 步骤事件（实时 + 回放同构） */
export interface StepEvent {
  runId: string;
  stepId: string;
  stepName: string;
  status: string;
  message: string;
  timestamp: number;
}

export const analysisApi = {
  submit(file: File, instruction: string, skill?: string): Promise<RunStart> {
    const form = new FormData();
    form.append('file', file);
    form.append('instruction', instruction);
    if (skill) form.append('skill', skill);
    return request.upload('/api/analysis/runs', form);
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
};

/**
 * 订阅 run 的步骤流（EventSource）。
 * 返回关闭函数。onEvent 收到的是"合并视图"——同一步骤的 RUNNING 与终态按 stepId 更新。
 */
export function subscribeSteps(
  runId: string,
  onEvent: (event: StepEvent) => void,
  onError?: (message: string) => void,
): () => void {
  const source = new EventSource(`${SSE_BASE}/api/analysis/runs/${runId}/stream`);
  source.addEventListener('step', (e) => {
    try {
      onEvent(JSON.parse((e as MessageEvent).data) as StepEvent);
    } catch {
      // 忽略畸形事件
    }
  });
  source.onerror = () => {
    // EventSource 断开会自动重连；这里只上报，由调用方决定是否轮询兜底
    onError?.('SSE 连接中断，将按轮询兜底');
  };
  return () => source.close();
}

export { getErrorMessage };
