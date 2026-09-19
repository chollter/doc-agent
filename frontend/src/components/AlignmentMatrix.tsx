import { CheckCircle, AlertCircle, XCircle, TrendingUp, FileText } from 'lucide-react';

export interface AlignmentEntry {
  requirementId: string;
  requirement: string;
  status: 'MET' | 'PARTIAL' | 'MISSING';
  evidence: Array<{
    claim: string;
    sectionId: string;
    evidenceLevel: string;
  }>;
  gap: string;
  fix?: {
    type: string;
    before: string;
    after: string;
    roiScore: number;
    reason: string;
    effort: string;
  };
}

function StatusBadge({ status }: { status: 'MET' | 'PARTIAL' | 'MISSING' }) {
  const config = {
    MET: { icon: CheckCircle, label: '完全满足', className: 'bg-emerald-100 text-emerald-700 border-emerald-200' },
    PARTIAL: { icon: AlertCircle, label: '部分满足', className: 'bg-amber-100 text-amber-700 border-amber-200' },
    MISSING: { icon: XCircle, label: '不满足', className: 'bg-rose-100 text-rose-700 border-rose-200' },
  };
  const { icon: Icon, label, className } = config[status];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium ${className}`}>
      <Icon size={14} />
      {label}
    </span>
  );
}

function EvidenceLevelBadge({ level }: { level: string }) {
  const config: Record<string, { label: string; className: string }> = {
    L0_KEYWORD: { label: 'L0', className: 'bg-slate-100 text-slate-600' },
    L1_ACTIVITY: { label: 'L1', className: 'bg-blue-100 text-blue-700' },
    L2_METHOD: { label: 'L2', className: 'bg-indigo-100 text-indigo-700' },
    L3_RESULT: { label: 'L3', className: 'bg-violet-100 text-violet-700' },
    L4_TRADEOFF: { label: 'L4', className: 'bg-purple-100 text-purple-700' },
  };
  const { label, className } = config[level] ?? { label: level, className: 'bg-slate-100 text-slate-600' };
  return (
    <span className={`ml-2 rounded px-1.5 py-0.5 text-xs font-mono ${className}`} title={`证据等级：${level}`}>
      {label}
    </span>
  );
}

function SuggestionCard({ fix, priority }: { fix: AlignmentEntry['fix']; priority: string }) {
  if (!fix) return null;

  const priorityColor = priority === 'HIGH' ? 'border-rose-200 bg-rose-50' : 'border-amber-200 bg-amber-50';
  const roiColor = fix.roiScore >= 10 ? 'text-emerald-700' : fix.roiScore >= 5 ? 'text-amber-700' : 'text-slate-600';

  return (
    <div className={`rounded-lg border p-3 ${priorityColor}`}>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-xs font-medium text-slate-600">{fix.type === 'ENHANCE' ? '增强表述' : '补充内容'}</span>
        <span className={`flex items-center gap-1 text-xs font-bold ${roiColor}`}>
          <TrendingUp size={12} />
          +{fix.roiScore}分
        </span>
      </div>

      {fix.before && (
        <div className="mb-2 text-xs text-slate-500">
          <span className="font-medium">当前：</span>
          <span className="ml-1">{fix.before}</span>
        </div>
      )}

      <div className="mb-2 text-xs text-slate-700">
        <span className="font-medium">建议：</span>
        <span className="ml-1">{fix.after}</span>
      </div>

      <div className="text-xs text-slate-500">
        {fix.reason}
      </div>
    </div>
  );
}

export function AlignmentMatrix({ entries, showSuggestions = true }: {
  entries: AlignmentEntry[];
  showSuggestions?: boolean;
}) {
  if (!entries || entries.length === 0) {
    return null;
  }

  const metCount = entries.filter(e => e.status === 'MET').length;
  const partialCount = entries.filter(e => e.status === 'PARTIAL').length;
  const missingCount = entries.filter(e => e.status === 'MISSING').length;

  return (
    <div className="space-y-4">
      {/* 覆盖情况摘要 */}
      <div className="rounded-xl border border-slate-200 bg-white p-4">
        <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-slate-700">
          <FileText size={16} className="text-indigo-500" />
          要求覆盖情况
        </div>
        <div className="flex gap-4 text-sm">
          <span className="text-emerald-700">
            <span className="font-bold">{metCount}</span> 项完全满足
          </span>
          <span className="text-amber-700">
            <span className="font-bold">{partialCount}</span> 项部分满足
          </span>
          <span className="text-rose-700">
            <span className="font-bold">{missingCount}</span> 项缺失
          </span>
        </div>
      </div>

      {/* 对齐矩阵表格 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-slate-50">
              <tr>
                <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">岗位要求</th>
                <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">匹配状态</th>
                <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">简历证据</th>
                <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">差距</th>
                {showSuggestions && (
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">改进建议</th>
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {entries.map((entry) => (
                <tr key={entry.requirementId} className="hover:bg-slate-50">
                  <td className="px-4 py-3 text-sm text-slate-700">
                    {entry.requirement}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={entry.status} />
                  </td>
                  <td className="px-4 py-3">
                    {entry.evidence.length === 0 ? (
                      <span className="text-xs text-slate-400">无相关证据</span>
                    ) : (
                      <div className="space-y-2">
                        {entry.evidence.map((ev, idx) => (
                          <div key={idx} className="text-xs">
                            <span className="rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-indigo-700">
                              {ev.sectionId}
                            </span>
                            <span className="ml-2 text-slate-700">{ev.claim}</span>
                            <EvidenceLevelBadge level={ev.evidenceLevel} />
                          </div>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-xs text-slate-600">
                    {entry.gap || '—'}
                  </td>
                  {showSuggestions && (
                    <td className="px-4 py-3">
                      {entry.fix ? (
                        <SuggestionCard
                          fix={entry.fix}
                          priority={entry.status === 'MISSING' ? 'HIGH' : 'MEDIUM'}
                        />
                      ) : (
                        <span className="text-xs text-slate-400">无需改进</span>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
