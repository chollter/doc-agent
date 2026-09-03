import type { ReactNode } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';

function formatCost(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

/**
 * 执行过程统一折叠面板——实时时间线、阶段状态条、审计详情三块过程视图的收纳容器。
 * 默认收缩（完成后主屏让给分析结果）；头部保留一行状态徽章，
 * 步数/耗时/失败数在收缩状态也一眼可见——有问题不会被藏起来。
 */
export default function ExecutionPanel({ collapsed, onToggle, stepCount, failedCount, totalMs, children }: {
  collapsed: boolean;
  onToggle: () => void;
  stepCount: number;
  failedCount: number;
  totalMs: number;
  children: ReactNode;
}) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white shadow-sm">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center gap-2 px-5 py-3 text-left"
      >
        {collapsed ? <ChevronRight size={15} className="shrink-0 text-slate-400" /> : <ChevronDown size={15} className="shrink-0 text-slate-400" />}
        <span className="text-sm font-bold text-slate-800">执行过程</span>
        {stepCount > 0 ? (
          <>
            <span className="text-xs text-slate-400">{stepCount} 步 · 总耗时 {formatCost(totalMs)}</span>
            {failedCount > 0 ? (
              <span className="rounded bg-rose-50 px-1.5 py-0.5 text-[10px] font-semibold text-rose-600">
                {failedCount} 步失败——展开看原因
              </span>
            ) : (
              <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] text-emerald-600">全部成功</span>
            )}
          </>
        ) : (
          <span className="text-xs text-slate-400">提交后实时展示每一步</span>
        )}
      </button>
      {!collapsed && <div className="space-y-4 px-5 pb-5">{children}</div>}
    </div>
  );
}
