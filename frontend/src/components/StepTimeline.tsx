import { useState } from 'react';
import { Brain, ChevronDown, ChevronRight, ClipboardCheck, FileSearch, Wrench } from 'lucide-react';
import type { StepEvent } from '../api/analysis';

/** 步骤类型 → 图标与配色 */
function stepVisual(stepName: string) {
  if (stepName.startsWith('REACT_TOOL')) return { icon: Wrench, color: 'text-amber-600', bg: 'bg-amber-50', label: '工具' };
  if (stepName.startsWith('REACT_LLM')) return { icon: Brain, color: 'text-violet-600', bg: 'bg-violet-50', label: 'LLM' };
  if (stepName === 'REPORT' || stepName === 'RUN_FAILED') return { icon: ClipboardCheck, color: 'text-emerald-600', bg: 'bg-emerald-50', label: '报告' };
  return { icon: FileSearch, color: 'text-indigo-600', bg: 'bg-indigo-50', label: '流程' };
}

/** 步骤名美化：REACT_TOOL_CALL: read_section → 工具调用 read_section */
function prettyName(stepName: string): string {
  if (stepName.startsWith('REACT_TOOL_CALL: ')) return `调用工具 ${stepName.slice('REACT_TOOL_CALL: '.length)}`;
  if (stepName.startsWith('REACT_TOOL_TRACE: ')) return `工具返回 ${stepName.slice('REACT_TOOL_TRACE: '.length)}`;
  const map: Record<string, string> = {
    PARSE: '解析文档',
    REACT_ANALYZE: 'ReAct 自主分析',
    REACT_LLM_RESPONSE: 'LLM 推理',
    DIRECT_LLM: '直连 LLM 分析（降级）',
    RULE_FALLBACK: '规则摘要（兜底）',
    CITATION_VERIFY: '引用校验',
    REPORT: '生成报告',
    RUN_FAILED: '运行失败',
  };
  return map[stepName] ?? stepName;
}

function StatusDot({ status }: { status: string }) {
  if (status === 'RUNNING') {
    return <span className="inline-block h-2.5 w-2.5 animate-pulse rounded-full bg-blue-500" />;
  }
  if (status === 'SUCCESS') {
    return <span className="inline-block h-2.5 w-2.5 rounded-full bg-emerald-500" />;
  }
  return <span className="inline-block h-2.5 w-2.5 rounded-full bg-rose-500" />;
}

/**
 * Agent 执行时间线——演示的核心视觉。
 * 每步一张卡片：类型图标 + 美化名 + 状态 + 可展开的输入/输出摘要。
 */
export default function StepTimeline({ steps }: { steps: StepEvent[] }) {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  if (steps.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 p-8 text-center text-sm text-slate-400">
        上传文档并提交后，这里会实时展示 Agent 的每一步：解析 → 思考 → 工具调用 → 报告
      </div>
    );
  }

  return (
    <div className="flex flex-col">
      {steps.map((step) => {
        const { icon: Icon, color, bg } = stepVisual(step.stepName);
        const open = expanded[step.stepId];
        return (
          <div key={step.stepId} className="flex gap-3">
            <div className="flex flex-col items-center">
              <div className={`flex h-8 w-8 items-center justify-center rounded-full ${bg} ${color}`}>
                <Icon size={15} />
              </div>
              <div className="w-px flex-1 bg-slate-200" />
            </div>
            <div className="min-w-0 flex-1 pb-5">
              <button
                type="button"
                onClick={() => setExpanded((prev) => ({ ...prev, [step.stepId]: !prev[step.stepId] }))}
                className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-slate-50"
              >
                <StatusDot status={step.status} />
                <span className="text-sm font-medium text-slate-800">{prettyName(step.stepName)}</span>
                <span className="text-xs text-slate-400">{new Date(step.timestamp).toLocaleTimeString()}</span>
                {(step.message || '').trim().length > 0 && (
                  <span className="ml-auto text-slate-300">
                    {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  </span>
                )}
              </button>
              {open && step.message && (
                <pre className="mx-2 mt-1 max-h-48 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-900 px-3 py-2.5 text-xs leading-5 text-slate-200">
                  {step.message}
                </pre>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
