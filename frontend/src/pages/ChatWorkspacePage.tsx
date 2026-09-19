import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowRight,
  Check,
  CheckCircle2,
  Loader2,
  Send,
  ShieldAlert,
  Sparkles,
  Target,
  TrendingUp,
  Upload,
} from 'lucide-react';
import {
  analysisApi,
  getErrorMessage,
  type ActionableSuggestion,
  type AppliedRevision,
  type AuditStep,
  type FunnelVerdict,
} from '../api/analysis';
import ExecutionChain from '../components/ExecutionChain';

/** 对话消息——报告/建议/链路等都以消息形态进入对话流 */
type ChatMessage =
  | { id: string; role: 'user'; text: string }
  | { id: string; role: 'assistant'; text: string }
  | { id: string; role: 'report'; verdict: FunnelVerdict; summary: string; mode: string | null; round: number }
  | { id: string; role: 'suggestions'; runId: string; suggestions: ActionableSuggestion[]; round: number }
  | { id: string; role: 'compare'; before: FunnelVerdict; after: FunnelVerdict };

let seq = 0;
const nextId = () => `msg-${++seq}`;

/** 精简版报告消息（详细卡片去 /classic 看，对话里给结论级信息） */
function ReportBubble({ verdict, summary, mode }: { verdict: FunnelVerdict; summary: string; mode: string | null }) {
  return (
    <div className="space-y-2.5 rounded-2xl rounded-tl-sm border border-slate-200 bg-white p-4 shadow-sm">
      <div className="text-sm leading-7 text-slate-800">{summary}</div>

      {/* 定性评价（v6）：总评 + 强项/需注意——气泡里给结论级评价，不止指标标签 */}
      {verdict.evaluation && (
        <div className="space-y-2 rounded-xl bg-slate-50 p-3">
          {verdict.evaluation.overall && (
            <div className="text-xs font-semibold leading-6 text-slate-800">{verdict.evaluation.overall}</div>
          )}
          {((verdict.evaluation.strengths?.length ?? 0) > 0 || (verdict.evaluation.weaknesses?.length ?? 0) > 0) && (
            <div className="grid gap-2 md:grid-cols-2">
              {(verdict.evaluation.strengths?.length ?? 0) > 0 && (
                <div className="rounded-lg bg-emerald-50 p-2.5">
                  <div className="mb-1 flex items-center gap-1 text-[11px] font-semibold text-emerald-700">
                    <CheckCircle2 size={12} /> 强项
                  </div>
                  <ul className="space-y-0.5 text-[11px] leading-5 text-slate-700">
                    {verdict.evaluation.strengths!.map((s, i) => <li key={i}>· {s}</li>)}
                  </ul>
                </div>
              )}
              {(verdict.evaluation.weaknesses?.length ?? 0) > 0 && (
                <div className="rounded-lg bg-amber-50 p-2.5">
                  <div className="mb-1 flex items-center gap-1 text-[11px] font-semibold text-amber-700">
                    <ShieldAlert size={12} /> 需注意
                  </div>
                  <ul className="space-y-0.5 text-[11px] leading-5 text-slate-700">
                    {verdict.evaluation.weaknesses!.map((s, i) => <li key={i}>· {s}</li>)}
                  </ul>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {verdict.analysisDegraded && (
        <div className="rounded-lg bg-amber-50 px-3 py-2 text-[11px] leading-5 text-amber-700">
          事实层抽取降级：红旗为文本级粗查、画像缺失；其余角度基于 LLM 直读原文。
        </div>
      )}

      <div className="flex flex-wrap gap-1.5">
        {verdict.strength && (
          <span className={`rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${
            verdict.strength.band === 'STRONG' ? 'bg-emerald-100 text-emerald-700'
              : verdict.strength.band === 'MIXED' ? 'bg-amber-100 text-amber-700' : 'bg-rose-100 text-rose-700'}`}>
            内容强度 {verdict.strength.band === 'STRONG' ? '强' : verdict.strength.band === 'MIXED' ? '混合' : '弱'}
            <span className="ml-1 font-normal opacity-70">
              结果{Math.round(verdict.strength.resultRate * 100)}% · 主导{Math.round(verdict.strength.ownerRate * 100)}%
            </span>
          </span>
        )}
        {verdict.presentation && (
          <span className="rounded-full bg-indigo-100 px-2.5 py-0.5 text-[11px] font-semibold text-indigo-700">
            表达 {verdict.presentation.score} 分（{verdict.presentation.band} 档）
          </span>
        )}
        {verdict.matchMode === 'DIRECTION' && (
          <span className="flex items-center gap-1 rounded-full bg-sky-100 px-2.5 py-0.5 text-[11px] font-semibold text-sky-700">
            <Target size={11} /> 方向匹配
          </span>
        )}
        {verdict.matchMode === 'NONE' && verdict.recommendedDirections && verdict.recommendedDirections.length > 0 && (
          <span className="flex items-center gap-1 rounded-full bg-emerald-100 px-2.5 py-0.5 text-[11px] font-semibold text-emerald-700">
            <Target size={11} /> 最适方向「{(verdict.recommendedDirections.find((d) => d.tier === 'BEST_FIT') ?? verdict.recommendedDirections[0]).direction}」
          </span>
        )}
        {mode && <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-[11px] text-slate-500">{mode}</span>}
      </div>

      {verdict.redFlags && verdict.redFlags.length > 0 && (
        <div className="space-y-1">
          {(verdict.redFlags ?? []).slice(0, 4).map((f, i) => (
            <div key={i} className={`rounded-lg px-3 py-1.5 text-[11px] leading-5 ${
              f.severity === 'HIGH' ? 'bg-rose-50 text-rose-700' : f.severity === 'MEDIUM' ? 'bg-amber-50 text-amber-700' : 'bg-slate-50 text-slate-500'}`}>
              <ShieldAlert size={11} className="mr-1 inline" />[{f.severity}] {f.message}
            </div>
          ))}
          {verdict.redFlags.length > 4 && (
            <div className="text-[11px] text-slate-400">…共 {verdict.redFlags.length} 条</div>
          )}
        </div>
      )}

      {verdict.vocabularyGaps && verdict.vocabularyGaps.length > 0 && (
        <div className="rounded-lg bg-sky-50/70 px-3 py-2 text-[11px] leading-5 text-sky-700">
          {verdict.vocabularyGaps.slice(0, 3).map((v, i) => (
            <div key={i}>
              <div>「{v.usedSynonym}」→ 建议补术语「{v.term}」</div>
              {v.suggestion && <div className="text-[10px] text-sky-600">{v.suggestion}</div>}
            </div>
          ))}
        </div>
      )}

      {verdict.groundingFindings && verdict.groundingFindings.length > 0 && (
        <div className="rounded-lg bg-rose-50/70 px-3 py-2 text-[11px] leading-5 text-rose-600">
          落地性校验：{verdict.groundingFindings.length} 处建议含无出处数字/失锚引文（程序标记，采纳前注意核对）
        </div>
      )}
    </div>
  );
}

/** 前后对比卡：修改稿再分析后，53→75 的闭环在这里可见 */
function CompareBubble({ before, after }: { before: FunnelVerdict; after: FunnelVerdict }) {
  const rows: Array<{ label: string; a: string; b: string; better: boolean | null }> = [];
  const bandName = (b?: string) => (b === 'STRONG' ? '强' : b === 'MIXED' ? '混合' : b === 'WEAK' ? '弱' : b ?? '—');
  if (before.strength || after.strength) {
    rows.push({ label: '内容强度', a: bandName(before.strength?.band), b: bandName(after.strength?.band),
      better: before.strength?.band !== after.strength?.band });
  }
  if (before.presentation || after.presentation) {
    rows.push({ label: '表达分数', a: String(before.presentation?.score ?? '—'), b: String(after.presentation?.score ?? '—'),
      better: (after.presentation?.score ?? 0) > (before.presentation?.score ?? 0) ? true
        : (after.presentation?.score ?? 0) < (before.presentation?.score ?? 0) ? false : null });
  }
  const flagCount = (v: FunnelVerdict) => v.redFlags?.length ?? 0;
  rows.push({ label: '红旗数', a: String(flagCount(before)), b: String(flagCount(after)),
    better: flagCount(after) < flagCount(before) ? true : flagCount(after) > flagCount(before) ? false : null });
  const metCount = (v: FunnelVerdict) => v.mustHaveCoverage?.filter((c) => c.status === 'MET').length ?? 0;
  if (before.mustHaveCoverage || after.mustHaveCoverage) {
    rows.push({ label: '共性要求 MET', a: String(metCount(before)), b: String(metCount(after)),
      better: metCount(after) > metCount(before) ? true : metCount(after) < metCount(before) ? false : null });
  }

  return (
    <div className="rounded-2xl rounded-tl-sm border border-emerald-200 bg-gradient-to-br from-emerald-50/70 to-sky-50/50 p-4 shadow-sm">
      <div className="mb-2.5 flex items-center gap-1.5 text-sm font-bold text-emerald-700">
        <TrendingUp size={15} /> 修改前后对比
      </div>
      <div className="space-y-1">
        {rows.map((r, i) => (
          <div key={i} className="flex items-center gap-2 rounded-lg bg-white/80 px-3 py-1.5 text-xs">
            <span className="w-24 shrink-0 text-slate-500">{r.label}</span>
            <span className="flex-1 text-slate-500">{r.a}</span>
            <ArrowRight size={12} className="shrink-0 text-slate-300" />
            <span className="flex-1 font-semibold text-slate-700">{r.b}</span>
            {r.better === true && <span className="shrink-0 text-emerald-500">↑</span>}
            {r.better === false && <span className="shrink-0 text-rose-500">↓</span>}
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * 对话式简历工作台——上传在左、对话流占满其余宽度（右侧预览面板暂时下线）。
 * 分析报告/建议/链路都以消息进入对话流；采纳建议后修改稿暂存 state（面板恢复即用）。
 */
export default function ChatWorkspacePage() {
  const [file, setFile] = useState<File | null>(null);
  const [targetDirection, setTargetDirection] = useState('AI应用开发');
  const [persona, setPersona] = useState('');
  const [phase, setPhase] = useState<'idle' | 'running' | 'done' | 'failed'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [chainSteps, setChainSteps] = useState<AuditStep[]>([]);
  const [applied, setApplied] = useState<Set<number>>(new Set());
  // 修改稿暂存：右侧预览面板暂时下线，仅保留采纳状态的后端同步（恢复面板时读回返回值即可）
  const [, setRevision] = useState<AppliedRevision | null>(null);
  const [applying, setApplying] = useState(false);
  const [followUpText, setFollowUpText] = useState('');
  const [sendingFollowUp, setSendingFollowUp] = useState(false);
  const [lastRunId, setLastRunId] = useState<string | null>(null);
  // 最新结论暂存：再分析前后对比功能随右侧面板下线，恢复时读回 lastVerdict 即可
  const [, setLastVerdict] = useState<FunnelVerdict | null>(null);
  const [dragOver, setDragOver] = useState(false);

  const closeStreamRef = useRef<(() => void) | null>(null);
  const pollRef = useRef<number | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const cleanup = useCallback(() => {
    closeStreamRef.current?.();
    closeStreamRef.current = null;
    if (pollRef.current) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);
  useEffect(() => cleanup, [cleanup]);
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  /** 等待 run 完成，产出报告消息序列 */
  const awaitRun = useCallback((runId: string, userMsgText: string, isReAnalysis: boolean, prevVerdict: FunnelVerdict | null) => {
    setMessages((prev) => [...prev, { id: nextId(), role: 'user', text: userMsgText }]);
    
    // SSE 流式订阅：实时更新左侧栏 ExecutionChain
    const es = new EventSource(`/api/analysis/runs/${runId}/stream`);
    closeStreamRef.current = () => es.close();
    
    es.addEventListener('step', (e) => {
      const step = JSON.parse(e.data);
      setChainSteps((prev) => {
        const existing = prev.findIndex((s) => s.id === step.stepId);
        const auditStep: AuditStep = {
          id: step.stepId,
          parentStepId: null,
          stepName: step.stepName,
          status: step.status,
          inputSnapshot: null,
          outputSnapshot: step.message || null,
          llmUsed: false,
          toolUsed: null,
          costMs: 0,
          errorMessage: null,
          createdAt: new Date(step.timestamp).toISOString(),
        };
        if (existing >= 0) {
          const next = [...prev];
          next[existing] = auditStep;
          return next;
        }
        return [...prev, auditStep];
      });
    });
    
    es.onerror = () => {
      es.close();
      closeStreamRef.current = null;
      // SSE 断线回退到轮询（下方 setInterval 继续兜底）
    };
    
    pollRef.current = window.setInterval(async () => {
      try {
        const d = await analysisApi.getRun(runId);
        if (d.status === 'COMPLETED') {
          cleanup();
          setPhase('done');
          setLastRunId(runId);
          analysisApi.getAudit(runId).then((steps) => {
            setChainSteps(steps);
          }).catch(() => {});
          const verdict = d.result?.funnelVerdict ?? null;
          setLastVerdict(verdict);
          if (verdict && prevVerdict && isReAnalysis) {
            setMessages((prev) => [...prev, { id: nextId(), role: 'compare', before: prevVerdict, after: verdict }]);
          }
          if (d.result) {
            setMessages((prev) => [
              ...prev,
              { id: nextId(), role: 'report', verdict: verdict ?? ({} as FunnelVerdict), summary: d.result?.summary ?? '', mode: d.executionMode, round: 0 },
            ]);
            const suggestions = d.result.actionableSuggestions ?? [];
            if (suggestions.length > 0) {
              setMessages((prev) => [...prev, { id: nextId(), role: 'suggestions', runId, suggestions, round: 0 }]);
            }
          }
        } else if (d.status === 'FAILED') {
          cleanup();
          setPhase('failed');
          setError(d.lastError ?? '分析失败');
          setMessages((prev) => [...prev, { id: nextId(), role: 'assistant', text: `分析失败：${d.lastError ?? '未知错误'}` }]);
        }
      } catch {
        /* 下一轮轮询兜底 */
      }
    }, 2000);
  }, [cleanup]);

  const start = async () => {
    if (!file || phase === 'running') return;
    setError(null);
    setMessages([]);
    setApplied(new Set());
    setRevision(null);
    setChainSteps([]);
    setLastVerdict(null);
    setPhase('running');
    try {
      const { runId } = await analysisApi.submit(
        file, '按方向画像分析这份简历', 'resume-review',
        undefined,
        targetDirection.trim() || undefined,
        persona || undefined,
      );
      setLastRunId(runId);
      awaitRun(runId, `分析这份简历${targetDirection.trim() ? `（方向：${targetDirection.trim()}）` : ''}`, false, null);
    } catch (ex) {
      setPhase('failed');
      setError(getErrorMessage(ex));
    }
  };

  /** 采纳/取消一条建议 → 携带全部已选集请求修改稿 */
  const toggleApply = async (runId: string, index: number) => {
    if (applying || !lastRunId) return;
    const next = new Set(applied);
    if (next.has(index)) {
      next.delete(index);
    } else {
      next.add(index);
    }
    setApplied(next);
    if (next.size === 0) {
      setRevision(null);
      return;
    }
    setApplying(true);
    try {
      setRevision(await analysisApi.applySuggestions(runId, [...next]));
    } catch (ex) {
      setError(getErrorMessage(ex));
      // 失败回滚选择
      setApplied(applied);
    } finally {
      setApplying(false);
    }
  };

  /** 追问 */
  const sendFollowUp = async () => {
    if (!followUpText.trim() || !lastRunId || sendingFollowUp) return;
    const text = followUpText.trim();
    setFollowUpText('');
    setMessages((prev) => [...prev, { id: nextId(), role: 'user', text }]);
    setSendingFollowUp(true);
    try {
      await analysisApi.followUp(lastRunId, text);
      // 轮询消息直到新 ASSISTANT 消息出现
      const before = messages.length;
      const timer = window.setInterval(async () => {
        const msgs = await analysisApi.getMessages(lastRunId).catch(() => []);
        const last = msgs[msgs.length - 1];
        if (last && last.role === 'ASSISTANT') {
          window.clearInterval(timer);
          setSendingFollowUp(false);
          setMessages((prev) => {
            if (prev.some((m) => m.role === 'assistant' && m.text === last.content)) return prev;
            return [...prev, { id: nextId(), role: 'assistant', text: last.content }];
          });
        }
      }, 1500);
      void before;
    } catch (ex) {
      setSendingFollowUp(false);
      setError(getErrorMessage(ex));
    }
  };

  const running = phase === 'running';

  return (
    <div className="grid h-screen grid-cols-[260px_minmax(0,1fr)] gap-3 p-3">
      {/* 左：上传与参数 */}
      <div className="flex min-w-0 flex-col gap-3">
        <div
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragOver(false);
            const f = e.dataTransfer.files?.[0];
            if (f) setFile(f);
          }}
          className={`flex flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed p-5 text-center transition-colors ${
            dragOver ? 'border-indigo-400 bg-indigo-50' : 'border-slate-200 bg-white'}`}
        >
          <Upload size={20} className="text-indigo-400" />
          <div className="text-xs font-medium text-slate-600">{file ? file.name : '拖入或选择简历'}</div>
          <div className="text-[10px] text-slate-400">PDF / Word / Markdown</div>
          <input
            type="file"
            accept=".pdf,.docx,.doc,.md,.txt"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className="hidden"
            id="chat-file-input"
          />
          <label htmlFor="chat-file-input" className="cursor-pointer rounded-lg border border-slate-200 px-2.5 py-1 text-[11px] text-slate-600 hover:bg-slate-50">
            选择文件
          </label>
        </div>

        <div className="space-y-2.5 rounded-2xl border border-slate-200 bg-white p-3">
          <label className="block text-xs">
            <span className="mb-1 block font-semibold text-slate-500">求职方向</span>
            <input
              value={targetDirection}
              onChange={(e) => setTargetDirection(e.target.value)}
              placeholder="如 AI 应用开发"
              className="w-full rounded-lg border border-slate-200 px-2 py-1.5 text-xs outline-none focus:border-indigo-400"
            />
          </label>
          <div>
            <span className="mb-1 block text-xs font-semibold text-slate-500">人群</span>
            <div className="flex flex-wrap gap-1">
              {[
                { v: '', l: '自动' }, { v: 'NEW_GRAD', l: '应届' },
                { v: 'SENIOR', l: '资深' }, { v: 'CAREER_SWITCH', l: '转行' },
              ].map((p) => (
                <button
                  key={p.v}
                  type="button"
                  onClick={() => setPersona(p.v)}
                  className={`rounded-full px-2.5 py-0.5 text-[11px] ${persona === p.v ? 'bg-indigo-600 text-white' : 'border border-slate-200 text-slate-500 hover:bg-indigo-50'}`}
                >
                  {p.l}
                </button>
              ))}
            </div>
          </div>
        </div>

        <button
          type="button"
          onClick={start}
          disabled={!file || running}
          className="flex items-center justify-center gap-2 rounded-xl bg-indigo-600 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:bg-slate-300"
        >
          {running ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
          {running ? '分析中…' : '开始分析'}
        </button>

        {error && <div className="rounded-xl bg-rose-50 px-3 py-2 text-[11px] leading-5 text-rose-600">{error}</div>}

        {chainSteps.length > 0 && (
          <div className="min-h-0 flex-1 overflow-hidden">
            <ExecutionChain steps={chainSteps} defaultCollapsed />
          </div>
        )}
      </div>

      {/* 中：对话流 */}
      <div className="flex min-w-0 flex-col rounded-2xl border border-slate-200 bg-slate-50/60">
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
          {messages.length === 0 && (
            <div className="flex h-full flex-col items-center justify-center gap-2 text-center text-sm text-slate-400">
              <Sparkles size={22} className="text-indigo-300" />
              上传简历并开始分析——报告、建议、面试预演都会出现在这个对话里。
            </div>
          )}
          {messages.map((m) => {
            if (m.role === 'user') {
              return (
                <div key={m.id} className="flex justify-end">
                  <div className="max-w-[80%] rounded-2xl rounded-tr-sm bg-indigo-600 px-3.5 py-2 text-sm leading-6 text-white">
                    {m.text}
                  </div>
                </div>
              );
            }
            if (m.role === 'assistant') {
              return (
                <div key={m.id} className="max-w-[92%] whitespace-pre-wrap rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-2.5 text-sm leading-7 text-slate-700 shadow-sm">
                  {m.text}
                </div>
              );
            }
            if (m.role === 'report') {
              return <ReportBubble key={m.id} verdict={m.verdict} summary={m.summary} mode={m.mode} />;
            }
            if (m.role === 'compare') {
              return <CompareBubble key={m.id} before={m.before} after={m.after} />;
            }
            // suggestions
            return (
              <div key={m.id} className="max-w-[95%] space-y-2">
                <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-500">
                  <CheckCircle2 size={13} className="text-indigo-400" />
                  改进建议（点击采纳）
                </div>
                {m.suggestions.map((s, i) => {
                  const isApplied = applied.has(i);
                  return (
                    <div key={i} className={`rounded-2xl rounded-tl-sm border p-3.5 shadow-sm transition-colors ${
                      isApplied ? 'border-emerald-300 bg-emerald-50/60' : 'border-slate-200 bg-white'}`}>
                      <div className="mb-2 flex items-center gap-2">
                        <span className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${
                          s.severity === 'HIGH' ? 'bg-rose-100 text-rose-600'
                            : s.severity === 'MEDIUM' ? 'bg-amber-100 text-amber-600' : 'bg-slate-100 text-slate-500'}`}>
                          {s.severity}
                        </span>
                        <span className="text-xs font-medium text-slate-600">{s.target}</span>
                        {s.sectionId && (
                          <span className="rounded bg-sky-50 px-1.5 py-0.5 text-[10px] text-sky-600">
                            {s.sectionId}
                          </span>
                        )}
                        <button
                          type="button"
                          disabled={applying}
                          onClick={() => toggleApply(m.runId, i)}
                          className={`ml-auto flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold transition-colors ${
                            isApplied
                              ? 'bg-emerald-600 text-white'
                              : 'border border-indigo-200 bg-white text-indigo-600 hover:bg-indigo-50'}`}
                        >
                          {isApplied ? <Check size={12} /> : <CheckCircle2 size={12} />}
                          {isApplied ? '已采纳' : '采纳'}
                        </button>
                      </div>
                      <div className="space-y-1.5 text-xs leading-6">
                        <div className="rounded-lg bg-slate-50 px-2.5 py-1.5 text-slate-500 line-through decoration-rose-300">
                          {s.before}
                        </div>
                        <div className="rounded-lg bg-emerald-50 px-2.5 py-1.5 font-medium text-slate-700">
                          {s.after}
                        </div>
                        <div className="text-[11px] text-slate-400">{s.reason}</div>
                      </div>
                    </div>
                  );
                })}
              </div>
            );
          })}
          {(running || sendingFollowUp) && (
            <div className="flex items-center gap-2 rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-2.5 text-xs text-slate-400 shadow-sm">
              <Loader2 size={13} className="animate-spin" />
              {running ? 'Agent 正在分析（解析 → 抽取 → 红旗 → 分析 → 校验）…' : '思考中…'}
            </div>
          )}
          <div ref={bottomRef} />
        </div>

        {/* 追问输入 */}
        <div className="border-t border-slate-200 bg-white p-3">
          <div className="flex items-end gap-2">
            <textarea
              value={followUpText}
              onChange={(e) => setFollowUpText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  sendFollowUp();
                }
              }}
              rows={1}
              disabled={phase !== 'done' || !lastRunId}
              placeholder={phase === 'done' ? '追问任何问题（如"空窗期怎么解释"）…' : '分析完成后可追问'}
              className="max-h-28 flex-1 resize-none rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-indigo-400 disabled:bg-slate-50"
            />
            <button
              type="button"
              onClick={sendFollowUp}
              disabled={phase !== 'done' || !followUpText.trim() || sendingFollowUp}
              className="flex h-9 w-9 items-center justify-center rounded-xl bg-indigo-600 text-white hover:bg-indigo-700 disabled:bg-slate-300"
            >
              <Send size={15} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
