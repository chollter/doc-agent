import { AlertTriangle, Bookmark, ClipboardList, Lightbulb, ListChecks } from 'lucide-react';
import type { AnalysisResult } from '../api/analysis';

function ModeBadge({ mode }: { mode: string | null | undefined }) {
  if (!mode) return null;
  const config: Record<string, string> = {
    REACT: 'bg-violet-100 text-violet-700',
    LLM: 'bg-blue-100 text-blue-700',
    FALLBACK: 'bg-amber-100 text-amber-700',
  };
  const label: Record<string, string> = {
    REACT: 'ReAct 工具循环',
    LLM: '直连 LLM（降级）',
    FALLBACK: '规则摘要（兜底）',
  };
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${config[mode] ?? 'bg-slate-100 text-slate-600'}`}>
      {label[mode] ?? mode}
    </span>
  );
}

function Section({ icon: Icon, title, items, color }: {
  icon: typeof ListChecks;
  title: string;
  items: string[];
  color: string;
}) {
  if (!items || items.length === 0) return null;
  return (
    <div>
      <div className={`mb-2 flex items-center gap-1.5 text-sm font-semibold ${color}`}>
        <Icon size={15} />
        {title}
      </div>
      <ul className="space-y-1.5">
        {items.map((item, i) => (
          <li key={i} className="rounded-lg bg-slate-50 px-3 py-2 text-sm leading-6 text-slate-700">
            {item}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * 分析报告卡片：摘要 + 关键点 + 风险 + 建议 + 引用。
 * onCitation 点击引用时定位到文档面板对应节。
 */
export default function ReportCard({ result, mode, onCitation }: {
  result: AnalysisResult;
  mode: string | null | undefined;
  onCitation?: (sectionId: string) => void;
}) {
  return (
    <div className="space-y-5 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-base font-bold text-slate-800">分析报告</h3>
        <ModeBadge mode={mode} />
      </div>

      <div className="rounded-xl bg-gradient-to-br from-indigo-50 to-violet-50 p-4 text-sm leading-7 text-slate-800">
        {result.summary}
      </div>

      <Section icon={ListChecks} title="关键内容" items={result.keyPoints} color="text-indigo-600" />
      <Section icon={AlertTriangle} title="风险与问题" items={result.risks} color="text-rose-600" />
      <Section icon={Lightbulb} title="建议" items={result.suggestions} color="text-emerald-600" />

      {result.citations?.length > 0 && (
        <div>
          <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-slate-600">
            <Bookmark size={15} />
            引用证据（{result.citations.length} 条，点击定位原文）
          </div>
          <div className="flex flex-wrap gap-2">
            {result.citations.map((c, i) => (
              <button
                key={i}
                type="button"
                onClick={() => onCitation?.(c.sectionId)}
                className="group flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-600 transition-colors hover:border-indigo-300 hover:bg-indigo-50"
                title={c.quote}
              >
                <ClipboardList size={12} className="text-indigo-400" />
                <span className="font-mono font-semibold text-indigo-600">{c.sectionId}</span>
                <span className="max-w-52 truncate">{c.quote}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
