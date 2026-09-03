import { useState, type ReactElement } from 'react';
import { Brain, CheckCircle2, ChevronDown, ChevronRight, ClipboardCheck, Database, FileSearch, ShieldAlert, Wrench, XCircle } from 'lucide-react';
import type { AuditStep } from '../api/analysis';

/** 步骤名 → 中文 + 分组语义（链路哪个阶段） */
const STEP_META: Record<string, { label: string; phase: string; icon: typeof FileSearch; tone: string }> = {
  PARSE: { label: '解析文档', phase: '准备', icon: FileSearch, tone: 'text-indigo-600 bg-indigo-50' },
  ENTITY_EXTRACT: { label: '实体抽取', phase: '事实层', icon: Database, tone: 'text-cyan-600 bg-cyan-50' },
  PROFILE_BUILD: { label: '画像构建', phase: '事实层', icon: Database, tone: 'text-cyan-600 bg-cyan-50' },
  RED_FLAG_CHECK: { label: '红旗筛查', phase: '事实层', icon: ShieldAlert, tone: 'text-rose-600 bg-rose-50' },
  REACT_ANALYZE: { label: 'ReAct 自主分析', phase: '分析', icon: Brain, tone: 'text-violet-600 bg-violet-50' },
  REACT_LLM_RESPONSE: { label: 'LLM 推理', phase: '分析', icon: Brain, tone: 'text-violet-600 bg-violet-50' },
  DIRECT_LLM: { label: '直连 LLM（降级）', phase: '分析', icon: Brain, tone: 'text-violet-600 bg-violet-50' },
  RULE_FALLBACK: { label: '规则摘要（兜底）', phase: '分析', icon: FileSearch, tone: 'text-amber-600 bg-amber-50' },
  CITATION_VERIFY: { label: '引用校验', phase: '报告', icon: ClipboardCheck, tone: 'text-emerald-600 bg-emerald-50' },
  REPORT: { label: '生成报告', phase: '报告', icon: ClipboardCheck, tone: 'text-emerald-600 bg-emerald-50' },
  FOLLOW_UP: { label: '追问轮', phase: '追问', icon: Brain, tone: 'text-sky-600 bg-sky-50' },
  FOLLOW_UP_ANSWER: { label: '追问回答', phase: '追问', icon: Brain, tone: 'text-sky-600 bg-sky-50' },
  LOOP_RESUME: { label: '断点续跑', phase: '自愈', icon: CheckCircle2, tone: 'text-amber-600 bg-amber-50' },
  RUN_FAILED: { label: '运行失败', phase: '失败', icon: XCircle, tone: 'text-rose-600 bg-rose-50' },
};

function stepMeta(stepName: string) {
  if (stepName.startsWith('REACT_TOOL_CALL: ')) {
    return { label: `调用工具 ${stepName.slice('REACT_TOOL_CALL: '.length)}`, phase: '分析', icon: Wrench, tone: 'text-amber-600 bg-amber-50' };
  }
  if (stepName.startsWith('REACT_TOOL_TRACE: ')) {
    return { label: `工具返回 ${stepName.slice('REACT_TOOL_TRACE: '.length)}`, phase: '分析', icon: Wrench, tone: 'text-slate-500 bg-slate-50' };
  }
  return STEP_META[stepName] ?? { label: stepName, phase: '其他', icon: FileSearch, tone: 'text-slate-600 bg-slate-50' };
}

function formatCost(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

/**
 * 执行链路面板——一次运行"哪一步、多久、成功与否、为什么"的全部现场。
 * 关键设计：错误与输出摘要完整展开（不截断）——DEGRADED 标记、JSON 解析失败原因
 * 这类排障线索都在 outputSnapshot/errorMessage 里，截断一行等于丢掉线索。
 */
export default function ExecutionChain({ steps, defaultCollapsed = false }: {
  steps: AuditStep[];
  defaultCollapsed?: boolean;
}) {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [collapsed, setCollapsed] = useState(defaultCollapsed);

  if (steps.length === 0) {
    return <div className="rounded-xl border border-dashed border-slate-200 p-4 text-center text-xs text-slate-400">无执行链路数据</div>;
  }

  const byParent = new Map<string | null, AuditStep[]>();
  for (const s of steps) {
    const key = s.parentStepId ?? null;
    byParent.set(key, [...(byParent.get(key) ?? []), s]);
  }

  const failed = steps.filter((s) => s.status === 'FAILED').length;
  const totalMs = steps.reduce((acc, s) => acc + (s.costMs ?? 0), 0);

  const renderRow = (s: AuditStep, depth: number): ReactElement[] => {
    const meta = stepMeta(s.stepName);
    const Icon = meta.icon;
    const open = expanded[s.id];
    const hasDetail = Boolean(s.errorMessage || s.outputSnapshot || s.inputSnapshot);
    const children = byParent.get(s.id) ?? [];
    return [
      <div key={s.id}>
        <button
          type="button"
          disabled={!hasDetail && children.length === 0}
          onClick={() => setExpanded((p) => ({ ...p, [s.id]: !p[s.id] }))}
          className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs hover:bg-slate-50 ${!hasDetail ? 'cursor-default' : ''}`}
          style={{ paddingLeft: 8 + depth * 18 }}
        >
          {hasDetail ? (
            open ? <ChevronDown size={12} className="shrink-0 text-slate-400" /> : <ChevronRight size={12} className="shrink-0 text-slate-400" />
          ) : (
            <span className="w-3 shrink-0" />
          )}
          <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded ${meta.tone}`}>
            <Icon size={12} />
          </span>
          <span className="shrink-0 font-medium text-slate-700">{meta.label}</span>
          <span className={`shrink-0 rounded px-1.5 text-[10px] ${meta.tone}`}>{meta.phase}</span>
          <span className="shrink-0 text-slate-400">{formatCost(s.costMs ?? 0)}</span>
          {s.llmUsed && <span className="shrink-0 rounded bg-violet-50 px-1.5 text-[10px] text-violet-600">LLM</span>}
          {s.status === 'FAILED' && (
            <span className="flex shrink-0 items-center gap-1 rounded bg-rose-50 px-1.5 text-[10px] font-semibold text-rose-600">
              <XCircle size={10} /> 失败
            </span>
          )}
          {!open && s.outputSnapshot && (
            <span className={`truncate ${s.outputSnapshot.includes('DEGRADED') ? 'font-semibold text-amber-600' : 'text-slate-400'}`}>
              {s.outputSnapshot.split('\n')[0]}
            </span>
          )}
          <span className="ml-auto shrink-0 text-slate-300">{s.stepName}</span>
        </button>
        {open && hasDetail && (
          <div className="mb-1 space-y-1 rounded-lg bg-slate-50 py-2 text-[11px] leading-5" style={{ marginLeft: 8 + depth * 18 }}>
            {s.inputSnapshot && (
              <div className="px-3">
                <span className="font-semibold text-slate-500">输入：</span>
                <pre className="mt-0.5 max-h-32 overflow-auto whitespace-pre-wrap break-all text-slate-600">{s.inputSnapshot}</pre>
              </div>
            )}
            {s.outputSnapshot && (
              <div className="px-3">
                <span className="font-semibold text-slate-500">输出：</span>
                <pre className="mt-0.5 max-h-32 overflow-auto whitespace-pre-wrap break-all text-slate-600">{s.outputSnapshot}</pre>
              </div>
            )}
            {s.errorMessage && (
              <div className="px-3">
                <span className="font-semibold text-rose-500">错误：</span>
                <pre className="mt-0.5 max-h-32 overflow-auto whitespace-pre-wrap break-all text-rose-600">{s.errorMessage}</pre>
              </div>
            )}
          </div>
        )}
      </div>,
      ...children.flatMap((child) => renderRow(child, depth + 1)),
    ];
  };

  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <button
        type="button"
        onClick={() => setCollapsed((c) => !c)}
        className="flex w-full items-center gap-2 px-3 py-2 text-xs font-semibold text-slate-600"
      >
        {collapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
        执行链路（{steps.length} 步 · 总耗时 {formatCost(totalMs)}）
        {failed > 0 ? (
          <span className="rounded bg-rose-50 px-1.5 py-0.5 text-[10px] font-semibold text-rose-600">{failed} 步失败——点开失败步骤看原因</span>
        ) : (
          <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] text-emerald-600">全部成功</span>
        )}
      </button>
      {!collapsed && (
        <div className="max-h-[480px] overflow-y-auto px-2 pb-2">
          {(byParent.get(null) ?? []).flatMap((s) => renderRow(s, 0))}
        </div>
      )}
    </div>
  );
}
