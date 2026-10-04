import { useEffect, useState } from 'react';
import { GitCompare } from 'lucide-react';
import { analysisApi, type IterationReport, type LandingStatus } from '../api/analysis';

const bandName = (b: string | null) => (b === 'STRONG' ? '强' : b === 'MIXED' ? '混合' : b === 'WEAK' ? '弱' : b ?? '—');
const bandRank: Record<string, number> = { WEAK: 0, MIXED: 1, STRONG: 2 };
const sevLabel: Record<string, string> = { HIGH: '高危', MEDIUM: '中风险', LOW: '提示' };

const landingBadge: Record<LandingStatus, { label: string; cls: string }> = {
  LANDED: { label: '已落地', cls: 'bg-emerald-100 text-emerald-700' },
  NOT_LANDED: { label: '未落地', cls: 'bg-rose-100 text-rose-700' },
  INDETERMINATE: { label: '无法判定', cls: 'bg-slate-100 text-slate-500' },
};

/**
 * 迭代对比区块——绑定基线的 run 完成后展示"改了什么、改法是否落地"。
 * 数据源是后端纯代码 diff（零 token、可复现）；degraded 时只提示不编造差异。
 */
export default function IterationSection({ runId }: { runId: string }) {
  const [report, setReport] = useState<IterationReport | null>(null);

  useEffect(() => {
    let alive = true;
    setReport(null);
    analysisApi.getIterationReport(runId)
      .then((r) => { if (alive) setReport(r); })
      .catch(() => {});
    return () => { alive = false; };
  }, [runId]);

  if (!report) return null;

  if (report.degraded) {
    return (
      <div className="mt-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-[11px] leading-5 text-slate-500">
        {report.baseRunId
          ? '已绑定基线，但基线缺少完整结论——本次无法真实迭代对比。'
          : '未绑定基线——本次非真实迭代对比；绑定上一版后可查看"改了什么、改法是否落地"。'}
      </div>
    );
  }

  const tone = (delta: number) => (delta > 0 ? 'text-emerald-700' : delta < 0 ? 'text-rose-700' : 'text-slate-500');
  const changes: Array<{ label: string; text: string; cls: string }> = [];
  if (report.strengthBandBefore !== report.strengthBandAfter) {
    const d = (bandRank[report.strengthBandAfter ?? ''] ?? -1) - (bandRank[report.strengthBandBefore ?? ''] ?? -1);
    changes.push({
      label: '内容强度',
      text: `${bandName(report.strengthBandBefore)} → ${bandName(report.strengthBandAfter)}`,
      cls: tone(d),
    });
  }
  if (report.presentationScoreBefore !== report.presentationScoreAfter) {
    const d = (report.presentationScoreAfter ?? 0) - (report.presentationScoreBefore ?? 0);
    changes.push({
      label: '表达分',
      text: `${report.presentationScoreBefore ?? '—'} → ${report.presentationScoreAfter ?? '—'}`,
      cls: tone(d),
    });
  }
  if (report.positioningChanged) {
    changes.push({
      label: '定位锚点',
      text: `${report.positioningBefore ?? '—'} → ${report.positioningAfter ?? '—'}`,
      cls: 'text-slate-600',
    });
  }

  const hasAny = changes.length > 0 || report.redFlagsAdded.length > 0 || report.redFlagsRemoved.length > 0
    || report.dimensionChanges.length > 0 || report.suggestionLandings.length > 0;

  return (
    <details className="mt-3 rounded-xl border border-indigo-100 bg-indigo-50/50 p-3" open>
      <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-semibold text-indigo-700">
        <GitCompare size={15} />
        迭代对比（相对已绑定基线 · 纯代码 diff）
      </summary>
      {!hasAny ? (
        <div className="mt-2 text-xs leading-5 text-slate-500">相对基线无结构性变化——本轮结论与上一版一致。</div>
      ) : (
        <div className="mt-2 space-y-2.5 text-xs leading-5">
          {changes.length > 0 && (
            <div className="space-y-1">
              {changes.map((c) => (
                <div key={c.label}>
                  <span className="mr-1.5 text-slate-400">{c.label}</span>
                  <span className={`font-medium ${c.cls}`}>{c.text}</span>
                </div>
              ))}
            </div>
          )}
          {report.dimensionChanges.length > 0 && (
            <div className="space-y-1">
              {report.dimensionChanges.map((d, i) => (
                <div key={`${d.dimension}-${i}`}>
                  <span className="mr-1.5 text-slate-400">{d.dimension}</span>
                  <span className="text-slate-600">{d.levelBefore ?? '（无）'} → {d.levelAfter ?? '（无）'}</span>
                </div>
              ))}
            </div>
          )}
          {(report.redFlagsRemoved.length > 0 || report.redFlagsAdded.length > 0) && (
            <div className="space-y-1">
              {report.redFlagsRemoved.map((f, i) => (
                <div key={`rm-${i}`} className="text-emerald-700">已消除【{sevLabel[f.severity] ?? f.severity}】{f.message}</div>
              ))}
              {report.redFlagsAdded.map((f, i) => (
                <div key={`add-${i}`} className="text-rose-700">新增【{sevLabel[f.severity] ?? f.severity}】{f.message}</div>
              ))}
            </div>
          )}
          {report.suggestionLandings.length > 0 && (
            <div className="space-y-1">
              <div className="text-slate-400">改法落地（基线建议 → 新版是否照改）</div>
              {report.suggestionLandings.map((s, i) => (
                <div key={`${s.target}-${i}`} className="flex items-center gap-2">
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ${landingBadge[s.status].cls}`}>
                    {landingBadge[s.status].label}
                  </span>
                  <span className="min-w-0 truncate text-slate-600">{s.target}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </details>
  );
}
