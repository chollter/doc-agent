import { useEffect, useRef } from 'react';
import { FileText } from 'lucide-react';
import type { DocumentView } from '../api/analysis';

/**
 * 文档面板：分节渲染已解析的文档；highlightSectionId 时滚动定位并高亮
 * （报告引用点击 → 原文出处的可视化闭环）。
 */
export default function DocumentPanel({ doc, highlight }: {
  doc: DocumentView | null;
  highlight: string | null;
}) {
  const refs = useRef<Record<string, HTMLDivElement | null>>({});

  useEffect(() => {
    if (highlight && refs.current[highlight]) {
      refs.current[highlight]?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [highlight]);

  if (!doc) {
    return (
      <div className="flex h-full items-center justify-center rounded-2xl border border-dashed border-slate-200 p-8 text-center text-sm text-slate-400">
        提交分析后，这里展示解析出的文档分节；点击报告引用可定位原文
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="flex items-center gap-2 border-b border-slate-100 px-4 py-3">
        <FileText size={15} className="text-indigo-500" />
        <span className="truncate text-sm font-semibold text-slate-700">{doc.fileName}</span>
        <span className="ml-auto rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500">
          {doc.sectionCount} 节
        </span>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-4">
        {doc.sections.map((s) => (
          <div
            key={s.id}
            ref={(el) => { refs.current[s.id] = el; }}
            className={`mb-3 rounded-xl border p-3 transition-all ${
              highlight === s.id
                ? 'border-indigo-400 bg-indigo-50 shadow-sm'
                : 'border-slate-100 bg-slate-50/60'
            }`}
          >
            <div className="mb-1 flex items-center gap-2">
              <span className="rounded bg-indigo-100 px-1.5 py-0.5 font-mono text-xs font-semibold text-indigo-700">
                {s.id}
              </span>
              {s.heading && <span className="text-xs font-semibold text-slate-600">{s.heading}</span>}
              {s.page != null && <span className="text-xs text-slate-400">第 {s.page} 页</span>}
              <span className="ml-auto text-xs text-slate-400">{s.charCount} 字</span>
            </div>
            <p className="whitespace-pre-wrap text-xs leading-6 text-slate-600">{s.text}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
