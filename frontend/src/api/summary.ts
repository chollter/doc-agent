import { request } from './request';

export interface SummaryStep {
  name: string;
  status: string;
  detail: string;
}

export interface TaskSection {
  title: string;
  purpose: string;
  findings: string[];
  citations: string[];
  uncertainties: string[];
}

export interface DocumentSummaryReport {
  runId: string;
  skill: string;
  status: string;
  executionMode: 'LLM' | 'FALLBACK' | string;
  taskType: string;
  goal: string;
  dimensions: string[];
  summary: string;
  keyPoints: string[];
  risks: string[];
  todos: string[];
  citations: string[];
  sections: TaskSection[];
  steps: SummaryStep[];
}

export const summaryApi = {
  run(file: File, instruction: string) {
    const form = new FormData();
    form.append('file', file);
    if (instruction.trim()) form.append('instruction', instruction.trim());
    return request.upload<DocumentSummaryReport>('/api/summary/runs', form);
  },
};
