import { useEffect, useState } from 'react';
import { analysisApi, type RunPipeline } from '../api/analysis';

/** 阶段名中文映射（未知阶段原样显示）。 */
const STAGE_LABEL: Record<string, string> = {
  ROUTING: '文档路由',
  ENTITY_EXTRACT: '实体抽取',
  PROFILE_BUILD: '画像构建',
  RED_FLAG_CHECK: '红旗筛查',
  REACT_ANALYZE: 'ReAct 循环',
  LOOP_RESUME: '循环续跑',
  DIRECT_LLM: '直连 LLM（单轮）',
  RULE_FALLBACK: '规则兜底（无 LLM）',
  REPORT: '报告生成',
  FOLLOW_UP: '追问',
  FOLLOW_UP_ANSWER: '追问回答',
};

const CALL_SITE_LABEL: Record<string, string> = {
  ENTITY_EXTRACT: '实体抽取',
  DIRECT_LLM: '直连分析',
  REACT: 'ReAct 循环',
  FOLLOW_UP: '追问',
};

function stageColor(status: string): string {
  if (status === 'SUCCESS') return 'bg-emerald-500';
  if (status === 'FAILED') return 'bg-rose-500';
  return 'bg-slate-400';
}

/**
 * 单次运行链路诊断条：每个阶段一个色块（绿=成功/红=失败），
 * 附 LLM 调用聚合（含失败次数）——"哪里断了、为什么断"一眼可见。
 */
export default function PipelineBar({ runId }: { runId: string }) {
  const [pipeline, setPipeline] = useState<RunPipeline | null>(null);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    analysisApi.getPipeline(runId)
      .then((p) => { if (!cancelled) setPipeline(p); })
      .catch(() => { /* 诊断视图获取失败不阻塞主报告 */ });
    return () => { cancelled = true; };
  }, [runId]);

  if (!pipeline || pipeline.stages.length === 0) return null;

  const failedStages = pipeline.stages.filter((s) => s.status === 'FAILED');
  const llmFailures = pipeline.llmCalls.reduce((n, g) => n + g.failures, 0);
  const hasProblem = failedStages.length > 0 || llmFailures > 0 || pipeline.analysisDegraded;

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3 text-xs">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex w-full flex-wrap items-center gap-1.5 text-left"
      >
        <span className="mr-1 font-semibold text-slate-600">执行链路</span>
        {pipeline.stages.map((s, i) => (
          <span key={i} className="flex items-center gap-1.5">
            {i > 0 && <span className="text-slate-300">→</span>}
            <span
              className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-white ${stageColor(s.status)}`}
              title={s.error ?? s.detail ?? ''}
            >
              {STAGE_LABEL[s.stage] ?? s.stage}
            </span>
          </span>
        ))}
        <span className="ml-auto text-slate-400">{expanded ? '收起' : '详情'}</span>
      </button>

      {hasProblem && !expanded && (
        <div className="mt-2 text-rose-600">
          {failedStages.map((s) => `「${STAGE_LABEL[s.stage] ?? s.stage}」失败`).join('；')}
          {llmFailures > 0 && `；LLM 调用失败 ${llmFailures} 次`}
          {pipeline.analysisDegraded && '；实体抽取降级（红旗筛查未运行）'}
          ——展开查看详情
        </div>
      )}

      {expanded && (
        <div className="mt-3 space-y-2">
          {pipeline.stages.map((s, i) => (
            <div key={i} className="rounded-lg bg-slate-50 p-2">
              <div className="flex items-center gap-2">
                <span className={`h-2 w-2 rounded-full ${stageColor(s.status)}`} />
                <span className="font-medium text-slate-700">{STAGE_LABEL[s.stage] ?? s.stage}</span>
                <span className="text-slate-400">{s.status}</span>
                {s.costMs != null && <span className="text-slate-400">{s.costMs}ms</span>}
              </div>
              {s.error && <div className="mt-1 break-all text-rose-600">{s.error}</div>}
              {!s.error && s.detail && <div className="mt-1 text-slate-500">{s.detail}</div>}
            </div>
          ))}
          {pipeline.llmCalls.length > 0 && (
            <div className="rounded-lg bg-slate-50 p-2">
              <div className="mb-1 font-medium text-slate-700">LLM 调用</div>
              {pipeline.llmCalls.map((g) => (
                <div key={g.callSite} className="flex items-center gap-2 text-slate-500">
                  <span>{CALL_SITE_LABEL[g.callSite] ?? g.callSite}</span>
                  <span>×{g.total}</span>
                  {g.failures > 0 && <span className="text-rose-600">失败 {g.failures}</span>}
                </div>
              ))}
              <div className="mt-1 text-slate-400">
                完整 prompt/response 见 GET /api/analysis/runs/{pipeline.runId}/interactions
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
