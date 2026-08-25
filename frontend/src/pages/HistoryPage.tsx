import { useEffect, useState } from 'react';
import { Loader2, RefreshCw } from 'lucide-react';
import { analysisApi, getErrorMessage, type AuditStep, type RunDetail, type RunSummary } from '../api/analysis';
import ReportCard from '../components/ReportCard';
import { durationSeconds, formatTime } from '../utils/format';

const STATUS_STYLE: Record<string, string> = {
  COMPLETED: 'bg-emerald-100 text-emerald-700',
  ANALYZING: 'bg-blue-100 text-blue-700',
  RUNNING: 'bg-blue-100 text-blue-700',
  FAILED: 'bg-rose-100 text-rose-700',
};

/** 审计步骤树：按 parentStepId 缩进渲染（后端已按时间排序）。 */
function AuditTree({ steps }: { steps: AuditStep[] }) {
  const byParent = new Map<string | null, AuditStep[]>();
  for (const s of steps) {
    const key = s.parentStepId ?? null;
    byParent.set(key, [...(byParent.get(key) ?? []), s]);
  }
  const render = (parent: string | null, depth: number): JSX.Element[] =>
    (byParent.get(parent) ?? []).flatMap((s) => [
      <div key={s.id} className="flex items-center gap-2 py-1 text-xs" style={{ paddingLeft: depth * 16 }}>
        <span
          className={`inline-block h-2 w-2 rounded-full ${
            s.status === 'SUCCESS' ? 'bg-emerald-500' : s.status === 'RUNNING' ? 'bg-blue-500' : 'bg-rose-500'
          }`}
        />
        <span className="font-medium text-slate-700">{s.stepName}</span>
        <span className="text-slate-400">{s.costMs}ms</span>
        {s.toolUsed && <span className="rounded bg-amber-50 px-1.5 text-amber-600">{s.toolUsed}</span>}
        {s.llmUsed && <span className="rounded bg-violet-50 px-1.5 text-violet-600">LLM</span>}
        {s.errorMessage && <span className="truncate text-rose-500">{s.errorMessage}</span>}
      </div>,
      ...render(s.id, depth + 1),
    ]);
  return <div className="max-h-72 overflow-y-auto rounded-xl bg-slate-50 p-3">{render(null, 0)}</div>;
}

export default function HistoryPage() {
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<{ detail: RunDetail; audit: AuditStep[] } | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setRuns(await analysisApi.listRuns());
    } catch (ex) {
      setError(getErrorMessage(ex));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const open = async (runId: string) => {
    setSelected(null);
    const [detail, audit] = await Promise.all([
      analysisApi.getRun(runId),
      analysisApi.getAudit(runId).catch(() => []),
    ]);
    setSelected({ detail, audit });
  };

  return (
    <div className="grid h-screen grid-cols-12 gap-4 p-4">
      <div className="col-span-6 flex min-w-0 flex-col">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-bold text-slate-800">历史记录</h2>
          <button
            type="button"
            onClick={load}
            className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs text-slate-600 hover:bg-slate-100"
          >
            <RefreshCw size={13} />
            刷新
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto rounded-2xl border border-slate-200 bg-white shadow-sm">
          {loading ? (
            <div className="flex h-32 items-center justify-center text-slate-400">
              <Loader2 size={18} className="mr-2 animate-spin" /> 加载中
            </div>
          ) : error ? (
            <div className="p-6 text-sm text-rose-600">{error}</div>
          ) : runs.length === 0 ? (
            <div className="p-6 text-sm text-slate-400">还没有分析记录，去工作台提交一份文档吧</div>
          ) : (
            runs.map((run) => (
              <button
                key={run.runId}
                type="button"
                onClick={() => open(run.runId).catch((ex) => setError(getErrorMessage(ex)))}
                className={`block w-full border-b border-slate-100 px-4 py-3 text-left transition-colors hover:bg-slate-50 ${
                  selected?.detail.runId === run.runId ? 'bg-indigo-50/60' : ''
                }`}
              >
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-medium text-slate-800">{run.fileName ?? '未知文件'}</span>
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs ${STATUS_STYLE[run.status] ?? 'bg-slate-100 text-slate-500'}`}>
                    {run.status}
                  </span>
                  {run.executionMode && (
                    <span className="shrink-0 rounded-full bg-violet-50 px-2 py-0.5 text-xs text-violet-600">
                      {run.executionMode}
                    </span>
                  )}
                  <span className="ml-auto shrink-0 text-xs text-slate-400">{formatTime(run.createdAt)}</span>
                </div>
                <div className="mt-1 truncate text-xs text-slate-500">{run.instruction}</div>
              </button>
            ))
          )}
        </div>
      </div>

      <div className="col-span-6 min-w-0 space-y-4 overflow-y-auto">
        {!selected ? (
          <div className="flex h-full items-center justify-center rounded-2xl border border-dashed border-slate-200 text-sm text-slate-400">
            点击左侧记录，回放该次 Agent 的完整执行链路与结果
          </div>
        ) : (
          <>
            <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-bold text-slate-800">{selected.detail.fileName}</h3>
                <span className="text-xs text-slate-400">
                  {formatTime(selected.detail.createdAt)} · 用时 {durationSeconds(selected.detail.createdAt, selected.detail.finishedAt)}
                </span>
              </div>
              <p className="mt-1.5 text-xs text-slate-500">要求：{selected.detail.instruction}</p>
              <div className="mt-3 text-xs font-semibold text-slate-500">执行链路（{selected.audit.length} 步）</div>
              <div className="mt-2">
                <AuditTree steps={selected.audit} />
              </div>
            </div>
            {selected.detail.result ? (
              <ReportCard result={selected.detail.result} mode={selected.detail.executionMode} />
            ) : (
              <div className="rounded-2xl border border-slate-200 bg-white p-5 text-sm text-slate-400 shadow-sm">
                {selected.detail.lastError ?? '该 run 没有产出结果'}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
