import { useEffect, useMemo, useState } from 'react';
import { ArrowRight, GitCompareArrows, Loader2, Minus, RefreshCw, TrendingDown, TrendingUp } from 'lucide-react';
import {
  analysisApi,
  getErrorMessage,
  type AngleDelta,
  type OptimizationCompare,
  type OptimizationHistoryItem,
} from '../api/analysis';
import { formatTime } from '../utils/format';

/** 单个角度的前后对照行 */
function DeltaRow({ delta }: { delta: AngleDelta }) {
  const visual = {
    IMPROVED: { icon: TrendingUp, cls: 'text-emerald-600 bg-emerald-50', label: '变准' },
    REGRESSED: { icon: TrendingDown, cls: 'text-rose-600 bg-rose-50', label: '变差' },
    UNCHANGED: { icon: Minus, cls: 'text-slate-400 bg-slate-50', label: '不变' },
    MISSING: { icon: Minus, cls: 'text-slate-300 bg-slate-50', label: '无数据' },
  }[delta.direction];
  const Icon = visual.icon;
  return (
    <div className="flex items-center gap-2 rounded-lg bg-white/80 px-3 py-2 text-xs">
      <span className="w-28 shrink-0 font-medium text-slate-700">{delta.angle}</span>
      <span className="flex-1 truncate text-slate-500">{delta.baseline}</span>
      <ArrowRight size={12} className="shrink-0 text-slate-300" />
      <span className="flex-1 truncate font-medium text-slate-700">{delta.candidate}</span>
      <span className={`flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${visual.cls}`}>
        <Icon size={11} />
        {visual.label}
      </span>
    </div>
  );
}

/**
 * 校准工作台——"测出来不准 → 改了什么 → 提升到多少"的记录与对照。
 * 左侧按文件分组列出带版本标注的运行；选基线与候选版本后拉取分角度 diff：
 * 变准的角度与同时变差的角度并列呈现——校准不许以破坏其他角度为代价。
 */
export default function CalibrationPage() {
  const [items, setItems] = useState<OptimizationHistoryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [baselineVersion, setBaselineVersion] = useState('');
  const [candidateVersion, setCandidateVersion] = useState('');
  const [comparing, setComparing] = useState(false);
  const [compare, setCompare] = useState<OptimizationCompare | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setItems(await analysisApi.getOptimizationHistory());
    } catch (ex) {
      setError(getErrorMessage(ex));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  /** 同一文件的版本去重列表（时间倒序，历史接口已倒序） */
  const versionsOfFile = useMemo(() => {
    if (!fileName) return [];
    const seen = new Set<string>();
    return items.filter((i) => {
      if (i.fileName !== fileName || !i.promptVersion) return false;
      if (seen.has(i.promptVersion)) return false;
      seen.add(i.promptVersion);
      return true;
    });
  }, [items, fileName]);

  const files = useMemo(() => {
    const map = new Map<string, number>();
    for (const i of items) {
      if (i.fileName) map.set(i.fileName, (map.get(i.fileName) ?? 0) + 1);
    }
    return [...map.entries()].sort((a, b) => b[1] - a[1]);
  }, [items]);

  const runCompare = async () => {
    if (!fileName || !baselineVersion || !candidateVersion) return;
    setComparing(true);
    setError(null);
    setCompare(null);
    try {
      setCompare(await analysisApi.compareOptimization({ fileName, baselineVersion, candidateVersion }));
    } catch (ex) {
      setError(getErrorMessage(ex));
    } finally {
      setComparing(false);
    }
  };

  return (
    <div className="grid h-screen grid-cols-12 gap-4 p-4">
      <div className="col-span-5 flex min-w-0 flex-col">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-bold text-slate-800">校准记录</h2>
          <button
            type="button"
            onClick={load}
            className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs text-slate-600 hover:bg-slate-100"
          >
            <RefreshCw size={13} /> 刷新
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto rounded-2xl border border-slate-200 bg-white shadow-sm">
          {loading ? (
            <div className="flex h-32 items-center justify-center text-slate-400">
              <Loader2 size={18} className="mr-2 animate-spin" /> 加载中
            </div>
          ) : error && items.length === 0 ? (
            <div className="p-6 text-sm text-rose-600">{error}</div>
          ) : files.length === 0 ? (
            <div className="p-6 text-sm text-slate-400">
              还没有运行记录。提交分析时填写"校准标注（版本 + 优化说明）"，改完重跑同一份文件，在这里对照前后差异。
            </div>
          ) : (
            files.map(([file, count]) => (
              <button
                key={file}
                type="button"
                onClick={() => {
                  setFileName(file);
                  setCompare(null);
                  setBaselineVersion('');
                  setCandidateVersion('');
                }}
                className={`block w-full border-b border-slate-100 px-4 py-3 text-left transition-colors hover:bg-slate-50 ${fileName === file ? 'bg-indigo-50/60' : ''}`}
              >
                <div className="truncate text-sm font-medium text-slate-800">{file}</div>
                <div className="mt-0.5 text-xs text-slate-400">{count} 次运行</div>
              </button>
            ))
          )}
        </div>
      </div>

      <div className="col-span-7 min-w-0 space-y-4 overflow-y-auto">
        {error && items.length > 0 && (
          <div className="rounded-xl bg-rose-50 px-3 py-2 text-xs text-rose-600">{error}</div>
        )}
        {!fileName ? (
          <div className="flex h-full items-center justify-center rounded-2xl border border-dashed border-slate-200 text-sm text-slate-400">
            左侧选择文件，对照它的不同版本
          </div>
        ) : (
          <>
            <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
              <h3 className="text-sm font-bold text-slate-800">{fileName}</h3>
              <div className="mt-1 text-xs text-slate-400">该文件的版本运行（时间倒序）</div>
              <div className="mt-3 space-y-1.5">
                {versionsOfFile.map((v) => (
                  <div key={v.runId} className="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs">
                    <span className="rounded-full bg-indigo-50 px-2 py-0.5 font-semibold text-indigo-600">{v.promptVersion}</span>
                    <span className="font-bold text-slate-700">{v.scoreOverall ?? '—'} 分</span>
                    <span className="truncate text-slate-400">{v.optimizationNote ?? '（无优化说明）'}</span>
                    <span className="ml-auto shrink-0 text-slate-300">{formatTime(v.createdAt)}</span>
                  </div>
                ))}
                {versionsOfFile.length === 0 && (
                  <div className="text-xs text-slate-400">该文件没有带版本标注的运行——在分析工作台提交时填写校准标注</div>
                )}
              </div>

              <div className="mt-4 flex items-end gap-2">
                <label className="flex-1 text-xs">
                  <span className="mb-1 block font-semibold text-slate-500">基线版本</span>
                  <select
                    value={baselineVersion}
                    onChange={(e) => setBaselineVersion(e.target.value)}
                    className="w-full rounded-lg border border-slate-200 px-2 py-1.5 text-xs"
                  >
                    <option value="">选择…</option>
                    {versionsOfFile.map((v) => (
                      <option key={v.runId} value={v.promptVersion!}>{v.promptVersion}</option>
                    ))}
                  </select>
                </label>
                <label className="flex-1 text-xs">
                  <span className="mb-1 block font-semibold text-slate-500">候选版本</span>
                  <select
                    value={candidateVersion}
                    onChange={(e) => setCandidateVersion(e.target.value)}
                    className="w-full rounded-lg border border-slate-200 px-2 py-1.5 text-xs"
                  >
                    <option value="">选择…</option>
                    {versionsOfFile.map((v) => (
                      <option key={v.runId} value={v.promptVersion!}>{v.promptVersion}</option>
                    ))}
                  </select>
                </label>
                <button
                  type="button"
                  disabled={!baselineVersion || !candidateVersion || baselineVersion === candidateVersion || comparing}
                  onClick={runCompare}
                  className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-xs font-semibold text-white hover:bg-indigo-700 disabled:bg-slate-300"
                >
                  {comparing ? <Loader2 size={13} className="animate-spin" /> : <GitCompareArrows size={13} />}
                  对照
                </button>
              </div>
            </div>

            {compare && (
              <div className="rounded-2xl border border-slate-200 bg-gradient-to-br from-sky-50/60 to-indigo-50/40 p-5 shadow-sm">
                <div className="mb-3 flex items-center gap-2 text-sm font-bold text-slate-800">
                  <GitCompareArrows size={15} className="text-indigo-500" />
                  {compare.baseline.promptVersion} → {compare.candidate.promptVersion} 的分角度对照
                </div>
                <div className="space-y-1.5">
                  {compare.deltas.map((d, i) => <DeltaRow key={i} delta={d} />)}
                </div>
                <div className="mt-3 rounded-lg bg-white/70 px-3 py-2 text-xs leading-5 text-slate-600">
                  {compare.conclusion}
                </div>
                <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-slate-400">
                  <span>基线 run：{compare.baseline.runId}</span>
                  <span>候选 run：{compare.candidate.runId}</span>
                  {compare.candidate.degraded && (
                    <span className="rounded bg-amber-50 px-1.5 py-0.5 font-semibold text-amber-600">候选运行为降级——结论不可信</span>
                  )}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
