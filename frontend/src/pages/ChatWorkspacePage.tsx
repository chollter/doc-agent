import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  GitCompare,
  History,
  Loader2,
  RotateCcw,
  Send,
  Sparkles,
  Upload,
} from 'lucide-react';
import {
  analysisApi,
  getErrorMessage,
  type ActionableSuggestion,
  type AnalysisResult,
  type AuditStep,
  type BaselineCandidate,
  type FunnelVerdict,
  type ResumeItem,
} from '../api/analysis';
import ExecutionChain from '../components/ExecutionChain';
import IterationSection from '../components/IterationSection';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/** 对话消息——报告/链路等都以消息形态进入对话流 */
type ChatMessage =
  | { id: string; role: 'user'; text: string }
  | { id: string; role: 'assistant'; text: string }
  | { id: string; role: 'report'; result: AnalysisResult | null; mode: string | null; round: number; runId: string }
  | { id: string; role: 'compare'; before: FunnelVerdict; after: FunnelVerdict };

let seq = 0;
const nextId = () => `msg-${++seq}`;

/** 去重关键词桶：红旗/需注意/建议命中同一桶视为同一件事，先出现的保留（红旗带严重度，优先） */
const ISSUE_BUCKETS: Array<[string, RegExp]> = [
  ['空窗', /空窗|空白期|待业|中断/i],
  ['量化', /量化|结果佐证|数据支撑|指标|qps|错误率|性能/],
  ['术语', /术语|关键词|检索词|同义词/],
  ['跳槽', /跳槽|频繁|短任期|稳定性/],
  ['表达', /动词|表述|表达|措辞|格式|排版/],
];

const bucketOf = (text: string): string | null =>
  ISSUE_BUCKETS.find(([, re]) => re.test(text))?.[0] ?? null;

/** 展示层分类：一眼区分时间线硬伤与内容/表达问题；只加前缀，不参与去重 */
const TAG_TIMELINE = /空窗|空白期|待业|中断|间隔|无在职|无就业|任期/;
const TAG_CONTENT = /量化|结果佐证|数据支撑|指标|动词|表述|表达|措辞|格式|排版|术语|关键词/;
const tagOf = (text: string): string | null =>
  TAG_TIMELINE.test(text) ? '时间线' : TAG_CONTENT.test(text) ? '内容' : null;

/**
 * 行动清单：红旗（按严重度）→ 需注意（与红旗去重）→ 未被引用的改写建议，≤4 条。
 * 每条尽量挂 actionableSuggestions 的 after 作为改法，匹配不上就只陈述问题——宁缺毋滥，不硬配。
 */
function buildActionItems(
  verdict: FunnelVerdict | null,
  weaknesses: string[],
  suggestions: ActionableSuggestion[],
): string[] {
  const rank = { HIGH: 0, MEDIUM: 1, LOW: 2 } as const;
  const sevLabel: Record<string, string> = { HIGH: '高危', MEDIUM: '中风险', LOW: '提示' };
  const taken = new Set<string>();
  const usedSug = new Set<number>();
  const lines: string[] = [];
  // 下方会渲染成「原文→判断→改法」卡片的建议（与 ReportBubble 的 slice(0,3) 保持一致）
  const cardTags = new Set(
    suggestions.slice(0, 3).map((s) => tagOf(`${s.target} ${s.reason}`)).filter((t): t is string => t !== null),
  );

  const matchFix = (text: string): string | null => {
    const b = bucketOf(text);
    const idx = suggestions.findIndex((s, i) => !usedSug.has(i)
      && ((b !== null && bucketOf(`${s.target} ${s.reason} ${s.after}`) === b) || text.includes(s.target)));
    if (idx < 0) return null;
    usedSug.add(idx);
    return suggestions[idx].after;
  };

  const push = (text: string, fix?: string, severity?: string) => {
    const b = bucketOf(text);
    if (b && taken.has(b)) return;
    if (b) taken.add(b);
    const resolved = fix ?? matchFix(text);
    const tag = tagOf(text);
    const head = severity ? `【${tag ? `${tag}·` : ''}${severity}】` : tag ? `【${tag}】` : '';
    // 同类问题下方已有卡片给改法时只指路，不在清单里复述改法
    const tail = !resolved && tag !== null && cardTags.has(tag) ? '（对应改法见下方卡片）' : '';
    lines.push(resolved ? `${head}${text} → 「${resolved}」` : `${head}${text}${tail}`);
  };

  [...(verdict?.redFlags ?? [])]
    .sort((a, b) => rank[a.severity] - rank[b.severity])
    .forEach((f) => push(f.message, undefined, sevLabel[f.severity] ?? f.severity));
  weaknesses.forEach((w) => push(w));
  suggestions.forEach((s, i) => {
    if (!usedSug.has(i)) push(`${s.target}「${s.before}」`, s.after);
  });
  return lines.slice(0, 4);
}

/** 把已有的原文引用带进摘要，避免结论只剩标签。这里只展示已有证据，不让前端自行推断。 */
function buildEvidenceItems(result: AnalysisResult | null): string[] {
  if (!result) return [];
  const items: string[] = [];
  const dimensions = result.funnelVerdict?.evaluation?.dimensions ?? [];
  dimensions.forEach((dimension) => {
    (dimension.evidence ?? []).forEach((evidence) => {
      if (evidence && !items.includes(evidence)) items.push(`${dimension.dimension}：${evidence}`);
    });
  });
  (result.funnelVerdict?.evidenceAssessments ?? []).forEach((assessment) => {
    const quote = assessment.sourceQuote || assessment.evidenceFound?.[0];
    if (quote && !items.includes(quote)) {
      items.push(`${assessment.sectionId ? `[${assessment.sectionId}] ` : ''}${quote}`);
    }
  });
  result.citations?.forEach((citation) => {
    const item = `[${citation.sectionId}] ${citation.quote}`;
    if (!items.some((existing) => existing.includes(citation.quote))) items.push(item);
  });
  return items.slice(0, 3);
}

/** 证据链：把一条原文、一个判断和一个改法放在同一张卡片里。 */
function EvidenceChain({ suggestion, citation }: {
  suggestion: ActionableSuggestion;
  citation?: { sectionId: string; quote: string };
}) {
  const quote = citation?.quote || suggestion.before;
  return (
    <div className="rounded-lg border border-slate-200 bg-slate-50/70 px-3 py-2 text-xs leading-5">
      <div className="mb-1 font-semibold text-slate-800">{suggestion.target}</div>
      <div className="rounded border-l-2 border-indigo-300 bg-white px-2 py-1 text-slate-600">
        <span className="mr-1 font-medium text-indigo-600">原文{citation?.sectionId ? ` · ${citation.sectionId}` : ''}</span>
        {quote || '未找到可展示的原文片段'}
      </div>
      <div className="mt-1 text-amber-700"><span className="font-medium">判断：</span>{suggestion.reason}</div>
      <div className="mt-1 text-emerald-700"><span className="font-medium">改法：</span>{suggestion.after}</div>
    </div>
  );
}

/** 区块小标题：左侧色条 + 灰色小字，统一气泡内总评/先处理/可以主打/依据的层级 */
function SectionTitle({ children }: { children: string }) {
  return (
    <div className="mb-1 flex items-center gap-1.5 text-xs font-semibold tracking-wide text-slate-500">
      <span className="h-3 w-1 rounded-full bg-indigo-300" />
      {children}
    </div>
  );
}

/** 总评引用角标：锚点是总评里真实出现的原文片段（数字或连续短句），点击展开出处 */
interface CitationAnchor {
  start: number;
  end: number;
  sectionId: string;
  quote: string;
}

/**
 * 在总评文本里定位引用锚点：先找整句原文，找不到再退到 7 字滑窗的最长公共片段。
 * 只做确定性字符串匹配，不推断——总评没引用的证据不挂角标。
 */
function findCitationAnchors(conclusion: string, sources: { sectionId: string; quote: string }[]): CitationAnchor[] {
  const anchors: CitationAnchor[] = [];
  const taken: Array<[number, number]> = [];
  const overlaps = (s: number, e: number) => taken.some(([ts, te]) => s < te && ts < e);
  sources.forEach(({ sectionId, quote }) => {
    const q = quote?.trim();
    if (!q || q.length < 5) return;
    let hit = conclusion.indexOf(q);
    let len = q.length;
    if (hit < 0) {
      for (let w = Math.min(q.length, 14); w >= 7 && hit < 0; w -= 1) {
        for (let i = 0; i + w <= q.length; i += 1) {
          const frag = q.slice(i, i + w);
          // 片段边缘的标点不进高亮/不参与匹配，避免「P50 63.8s、」这种带尾巴的角标
          const lead = frag.length - frag.replace(/^[，。、；：！？,.;:!?（(「『"']+/, '').length;
          const trail = frag.length - frag.replace(/[，。、；：！？,.;:!?）)」』"']+$/, '').length;
          if (lead + trail >= frag.length) continue;
          const core = frag.slice(lead, frag.length - trail);
          if (core.length < 6) continue;
          const idx = conclusion.indexOf(core);
          if (idx >= 0 && !overlaps(idx, idx + core.length)) { hit = idx; len = core.length; break; }
        }
      }
    }
    if (hit >= 0 && !overlaps(hit, hit + len)) {
      taken.push([hit, hit + len]);
      anchors.push({ start: hit, end: hit + len, sectionId, quote: q });
    }
  });
  return anchors.sort((a, b) => a.start - b.start);
}

/** 带角标的总评：正文按锚点切段，角标点击展开「依据 · 原文片段」 */
function AnchoredConclusion({ text, anchors }: { text: string; anchors: CitationAnchor[] }) {
  const [open, setOpen] = useState<number | null>(null);
  if (anchors.length === 0) return <p className="leading-7 text-slate-700">{text}</p>;
  const parts: ReactNode[] = [];
  let cursor = 0;
  anchors.forEach((a, i) => {
    if (a.start > cursor) parts.push(text.slice(cursor, a.start));
    parts.push(
      <span key={`seg-${i}`} className="rounded bg-indigo-50 text-indigo-700">{text.slice(a.start, a.end)}</span>,
    );
    parts.push(
      <sup key={`sup-${i}`}>
        <button
          type="button"
          onClick={() => setOpen(open === i ? null : i)}
          className="mx-0.5 cursor-pointer rounded px-1 text-[10px] font-semibold text-indigo-500 hover:bg-indigo-50 hover:text-indigo-700"
          title="查看依据原文"
        >
          [{i + 1}]
        </button>
      </sup>,
    );
    cursor = a.end;
  });
  if (cursor < text.length) parts.push(text.slice(cursor));
  const active = open !== null ? anchors[open] : null;
  return (
    <div>
      <p className="leading-7 text-slate-700">{parts}</p>
      {active && (
        <div className="mt-1 rounded border-l-2 border-indigo-300 bg-slate-50 px-2 py-1 text-xs leading-5 text-slate-600">
          <span className="mr-1 font-medium text-indigo-600">依据{active.sectionId ? ` · ${active.sectionId}` : ''}</span>
          {active.quote}
        </div>
      )}
    </div>
  );
}

/** 报告消息（精简版）：总评 → 先处理 → 可以主打 → 改法卡片 → 页脚倒金字塔；维度判断融进总评叙事，不单独成块 */
function ReportBubble({ result, mode, runId, onReanalyze }: {
  result: AnalysisResult | null;
  mode: string | null;
  runId: string;
  onReanalyze?: () => void;
}) {
  const verdict = result?.funnelVerdict ?? null;
  const ev = verdict?.evaluation ?? null;

  // L1 结论：evaluation.overall 优先，无评价时（历史/降级 run）回退 summary，二者只渲染其一。
  const conclusion = ev?.overall ?? result?.summary;

  // L2 先处理：红旗 + 需注意（无评价时回退旧字段 risks）合并去重，改法来自 actionableSuggestions。
  const weaknessPool = ev ? (ev.weaknesses ?? []) : (result?.risks ?? []);
  const actions = buildActionItems(verdict, weaknessPool, result?.actionableSuggestions ?? []);

  // L3 可以主打：strengths，无评价时回退旧字段 keyPoints。
  const highlights = (ev?.strengths?.length ? ev.strengths : result?.keyPoints ?? []).slice(0, 2);
  const evidence = buildEvidenceItems(result);
  const suggestions = result?.actionableSuggestions ?? [];
  const citations = result?.citations ?? [];
  const evidenceChains = suggestions.slice(0, 3).map((suggestion) => ({
    suggestion,
    citation: citations.find((citation) => citation.sectionId === suggestion.sectionId),
  }));

  const degraded = !!verdict?.analysisDegraded;
  const empty = !conclusion && actions.length === 0 && highlights.length === 0;

  // 页脚：画像/指标/定位压成一行小字（分维评价已在上方折叠给出，推荐方向等论据不进聊天回复）。
  const foot: string[] = [];
  if (result?.profile) {
    const p = result.profile;
    foot.push(`${p.name ?? '未具名'}·${p.yearsOfExperience}年${p.currentRole ? `·${p.currentRole}` : ''}`);
  }
  if (verdict?.strength) {
    const band = verdict.strength.band === 'STRONG' ? '强' : verdict.strength.band === 'MIXED' ? '混合' : '弱';
    const s = verdict.strength;
    // 给条数而非百分比：resultRate 是"有可核验结果的经历占比"，百分号会被误读成打分
    const counted = s.entryCount > 0 ? `（${Math.round(s.resultRate * s.entryCount)}/${s.entryCount}条有可验证结果）` : '';
    foot.push(`内容强度${band}${counted}`);
  }
  if (verdict?.presentation) foot.push(`表达${verdict.presentation.score}分（${verdict.presentation.band}档）`);
  if (verdict?.positioning) {
    foot.push(verdict.positioning.anchored
      ? `定位「${verdict.positioning.currentAnchor ?? '—'}」`
      : (verdict.positioning.suggestedAnchor ? `建议定位「${verdict.positioning.suggestedAnchor}」` : '定位未锚定'));
  }
  if (mode) {
    const modeLabel: Record<string, string> = {
      REACT: '完整分析', LLM: '降级直连', FALLBACK: '历史规则结果', MOCK: '演示数据（非真实分析）', CACHE_HIT: '历史结论·未重新分析',
    };
    foot.push(modeLabel[mode] ?? mode);
  }

  return (
    <div className="max-w-[92%] rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-2.5 text-sm leading-7 text-slate-700 shadow-sm">
      {conclusion ? (
        <section>
          <SectionTitle>总评</SectionTitle>
          <AnchoredConclusion text={conclusion} anchors={findCitationAnchors(conclusion, [
            ...citations,
            ...(ev?.dimensions ?? []).flatMap((d) => (d.evidence ?? []).map((quote) => ({ sectionId: d.dimension, quote }))),
          ])} />
        </section>
      ) : null}
      {actions.length > 0 && (
        <section className="mt-3">
          <SectionTitle>先处理</SectionTitle>
          <ol className="list-decimal space-y-1 pl-5 leading-6 marker:text-slate-400">
            {actions.map((a, i) => <li key={i}>{a}</li>)}
          </ol>
        </section>
      )}
      {highlights.length > 0 && (
        <section className="mt-3">
          <SectionTitle>可以主打</SectionTitle>
          <ul className="list-disc space-y-1 pl-5 leading-6 marker:text-slate-400">
            {highlights.map((h, i) => <li key={i}>{h}</li>)}
          </ul>
        </section>
      )}
      {evidence.length > 0 && evidenceChains.length === 0 && (
        <section className="mt-3">
          <SectionTitle>依据（原文）</SectionTitle>
          <ul className="list-disc space-y-1 pl-5 leading-6 marker:text-slate-400">
            {evidence.map((e, i) => <li key={i}>{e}</li>)}
          </ul>
        </section>
      )}
      {degraded && (
        <div className="mt-2 rounded-md border border-amber-200 bg-amber-50 px-2.5 py-1 text-xs leading-5 text-amber-700">
          ⚠️ 事实层抽取降级，红旗为文本级粗查、画像缺失；其余角度基于 LLM 直读原文。
        </div>
      )}
      {empty && <div className="whitespace-pre-wrap">（无分析结论）</div>}
      {evidenceChains.length > 0 && (
        <div className="mt-3 space-y-2 border-t border-slate-100 pt-3">
          <SectionTitle>原文 → 判断 → 改法</SectionTitle>
          {evidenceChains.map(({ suggestion, citation }) => (
            <EvidenceChain key={`${suggestion.target}-${suggestion.sectionId ?? 'unknown'}`} suggestion={suggestion} citation={citation} />
          ))}
        </div>
      )}
      <IterationSection runId={runId} />
      {foot.length > 0 && (
        <div className="mt-2 border-t border-slate-100 pt-1.5 text-[11px] leading-5 text-slate-400">
          {foot.join(' ｜ ')}
        </div>
      )}
      {onReanalyze && (
        <button
          type="button"
          onClick={onReanalyze}
          className="mt-1 flex items-center gap-1 text-[11px] text-slate-400 transition-colors hover:text-indigo-600"
        >
          <RotateCcw size={11} /> 重新分析
        </button>
      )}
    </div>
  );
}

/** 前后对比（精简版）：纯文本行 */
function CompareBubble({ before, after }: { before: FunnelVerdict; after: FunnelVerdict }) {
  const bandName = (b?: string) => (b === 'STRONG' ? '强' : b === 'MIXED' ? '混合' : b === 'WEAK' ? '弱' : b ?? '—');
  const flagCount = (v: FunnelVerdict) => v.redFlags?.length ?? 0;
  const lines = [`内容强度：${bandName(before.strength?.band)} → ${bandName(after.strength?.band)}`];
  if (before.presentation || after.presentation) {
    lines.push(`表达分数：${before.presentation?.score ?? '—'} → ${after.presentation?.score ?? '—'}`);
  }
  lines.push(`红旗数：${flagCount(before)} → ${flagCount(after)}`);
  const reasons: string[] = [];
  if (bandName(before.strength?.band) === bandName(after.strength?.band)) {
    reasons.push('内容强度未变：本轮修改没有改变可核验的结果或责任归因证据');
  }
  if (before.presentation?.score != null && before.presentation.score === after.presentation?.score) {
    reasons.push('表达分数未变：修改没有命中当前表达问题，或修改稿尚未重新进入评估');
  }
  if (flagCount(before) === flagCount(after)) {
    reasons.push('红旗未变：时间线等客观事实不会因措辞调整自动消失');
  }
  return (
    <div className="max-w-[92%] rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-2.5 text-sm leading-7 text-slate-700 shadow-sm">
      <div className="mb-1 text-xs font-semibold text-slate-500">修改前后对比</div>
      <div className="whitespace-pre-wrap">{lines.join('\n')}</div>
      {reasons.length > 0 && (
        <div className="mt-1 border-t border-slate-100 pt-1 text-xs leading-5 text-slate-500">
          {reasons.map((reason) => <div key={reason}>· {reason}</div>)}
        </div>
      )}
    </div>
  );
}

/**
 * 对话式简历工作台——上传在左、对话流占满其余宽度。
 * 报告以一段纯文本进入对话流（精简版，不卡片化）。
 */
export default function ChatWorkspacePage() {
  const [file, setFile] = useState<File | null>(null);
  const [targetDirection, setTargetDirection] = useState('AI应用开发');
  const [persona, setPersona] = useState('');
  const [phase, setPhase] = useState<'idle' | 'running' | 'done' | 'failed'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [chainSteps, setChainSteps] = useState<AuditStep[]>([]);
  const [followUpText, setFollowUpText] = useState('');
  const [sendingFollowUp, setSendingFollowUp] = useState(false);
  const [lastRunId, setLastRunId] = useState<string | null>(null);
  // 最新结论暂存：重新分析时用于前后对比（CompareBubble）
  const [lastVerdict, setLastVerdict] = useState<FunnelVerdict | null>(null);
  const [dragOver, setDragOver] = useState(false);
  // 历史简历（简历档案）：选中后免上传直接分析
  const [resumes, setResumes] = useState<ResumeItem[]>([]);
  const [selectedResumeId, setSelectedResumeId] = useState<string | null>(null);
  // 最近一次提交来源（文件/历史简历）——"重新分析"按原来源强制重跑
  const [lastSubmit, setLastSubmit] = useState<{ file: File | null; resumeId: string | null }>({ file: null, resumeId: null });
  // 迭代基线：候选列表供手动选定"上一版"，默认不绑定——与后端"不静默绑定"同一血缘口径
  const [candidates, setCandidates] = useState<BaselineCandidate[]>([]);
  const [baseRunId, setBaseRunId] = useState<string | null>(null);

  const selectedResume = resumes.find((r) => r.id === selectedResumeId) ?? null;

  const refreshResumes = useCallback(() => {
    analysisApi.listResumes().then(setResumes).catch(() => {});
  }, []);
  const refreshCandidates = useCallback(() => {
    analysisApi.getBaselineCandidates().then(setCandidates).catch(() => {});
  }, []);
  useEffect(() => { refreshResumes(); refreshCandidates(); }, [refreshResumes, refreshCandidates]);

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

  /** 等待 run 完成，产出报告消息 */
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
          if (d.result || d.summary) {
            setMessages((prev) => [
              ...prev,
              {
                id: nextId(), role: 'report',
                result: d.result,
                mode: d.executionMode,
                round: 0,
                runId,
              },
            ]);
            // 本次 run 完成即成为新的基线候选（新→旧排在最前）
            refreshCandidates();
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
  }, [cleanup, refreshCandidates]);

  /** 提交分析。forceRefresh=true：按最近一次提交的来源强制重跑（跳过结论缓存），完成后覆盖缓存并出对比 */
  const start = async (forceRefresh = false) => {
    const useFile = forceRefresh ? lastSubmit.file : file;
    const useResumeId = forceRefresh ? lastSubmit.resumeId : selectedResumeId;
    if ((!useFile && !useResumeId) || phase === 'running') return;
    setError(null);
    if (!forceRefresh) {
      setMessages([]);
      setChainSteps([]);
      setLastVerdict(null);
    }
    setPhase('running');
    try {
      const { runId } = await analysisApi.submit(
        useFile, '按方向画像分析这份简历', 'resume-review',
        undefined,
        targetDirection.trim() || undefined,
        persona || undefined,
        undefined,
        undefined,
        { resumeId: useResumeId ?? undefined, forceRefresh, baseRunId: baseRunId ?? undefined },
      );
      setLastRunId(runId);
      setLastSubmit({ file: useFile, resumeId: useResumeId });
      const dirSuffix = targetDirection.trim() ? `（方向：${targetDirection.trim()}）` : '';
      awaitRun(runId, `${forceRefresh ? '重新分析' : '分析'}这份简历${dirSuffix}`, forceRefresh, forceRefresh ? lastVerdict : null);
      // 提交即建档/更新使用计数，刷新档案列表
      refreshResumes();
    } catch (ex) {
      setPhase('failed');
      setError(getErrorMessage(ex));
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
            if (f) {
              setFile(f);
              setSelectedResumeId(null);
              setBaseRunId(null);
            }
          }}
          className={`flex flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed p-5 text-center transition-colors ${
            dragOver ? 'border-indigo-400 bg-indigo-50' : 'border-slate-200 bg-white'}`}
        >
          <Upload size={20} className="text-indigo-400" />
          <div className="max-w-full truncate text-xs font-medium text-slate-600">
            {file ? file.name : selectedResume ? `历史简历：${selectedResume.fileName ?? '未命名'}` : '拖入或选择简历'}
          </div>
          <div className="text-[10px] text-slate-400">PDF / Word / Markdown</div>
          <input
            type="file"
            accept=".pdf,.docx,.doc,.md,.txt"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setSelectedResumeId(null);
              setBaseRunId(null);
            }}
            className="hidden"
            id="chat-file-input"
          />
          <label htmlFor="chat-file-input" className="cursor-pointer rounded-lg border border-slate-200 px-2.5 py-1 text-[11px] text-slate-600 hover:bg-slate-50">
            选择文件
          </label>
        </div>

        {resumes.length > 0 && (
          <div className="rounded-2xl border border-slate-200 bg-white p-3">
            <div className="mb-1.5 flex items-center gap-1 text-xs font-semibold text-slate-500">
              <History size={13} /> 历史简历（免上传）
            </div>
            <div className="max-h-44 space-y-1 overflow-y-auto">
              {resumes.map((r) => (
                <button
                  key={r.id}
                  type="button"
                  onClick={() => {
                    if (selectedResumeId === r.id) {
                      setSelectedResumeId(null);
                    } else {
                      setSelectedResumeId(r.id);
                      setFile(null);
                    }
                    setBaseRunId(null);
                  }}
                  className={`flex w-full items-center justify-between gap-2 rounded-lg px-2 py-1.5 text-left text-[11px] transition-colors ${
                    selectedResumeId === r.id ? 'bg-indigo-50 text-indigo-700' : 'text-slate-600 hover:bg-slate-50'}`}
                >
                  <span className="min-w-0 truncate">{r.fileName ?? '（未命名）'}</span>
                  <span className="shrink-0 text-[10px] text-slate-400">
                    {r.charCount != null ? `${r.charCount}字 · ` : ''}分析{r.runCount ?? 0}次
                  </span>
                </button>
              ))}
            </div>
          </div>
        )}

        {candidates.length > 0 && (
          <div className="rounded-2xl border border-slate-200 bg-white p-3">
            <div className="mb-1.5 flex items-center gap-1 text-xs font-semibold text-slate-500">
              <GitCompare size={13} /> 迭代基线（可选）
            </div>
            <select
              value={baseRunId ?? ''}
              onChange={(e) => setBaseRunId(e.target.value || null)}
              className="w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-600 outline-none focus:border-indigo-400"
            >
              <option value="">不绑定基线（默认——非迭代对比）</option>
              {candidates.map((c) => (
                <option key={c.runId} value={c.runId}>
                  {c.fileName ?? '未命名'} · {c.promptVersion ?? '—'} · {new Date(c.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}
                </option>
              ))}
            </select>
            <div className="mt-1 text-[10px] leading-4 text-slate-400">
              {baseRunId
                ? '已绑定：本次将按血缘重评变化区域，未重评维度沿用基线并挂血缘。'
                : '选中"上一版"后本次才是真实迭代对比——系统不自动挑基线。'}
            </div>
          </div>
        )}

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
          onClick={() => start()}
          disabled={(!file && !selectedResumeId) || running}
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
              上传简历并开始分析——报告会出现在这个对话里。
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
                <div key={m.id} className="max-w-[92%] rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-2.5 text-sm leading-7 text-slate-700 shadow-sm">
                  <div className="prose prose-sm prose-slate max-w-none [&_p]:my-1.5 [&_ul]:my-1 [&_ol]:my-1 [&_li]:my-0.5 [&_strong]:text-slate-900">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.text}</ReactMarkdown>
                  </div>
                </div>
              );
            }
            if (m.role === 'report') {
              return (
                <ReportBubble
                  key={m.id}
                  result={m.result}
                  mode={m.mode}
                  runId={m.runId}
                  onReanalyze={running ? undefined : () => start(true)}
                />
              );
            }
            return <CompareBubble key={m.id} before={m.before} after={m.after} />;
          })}
          {(running || sendingFollowUp) && (
            <div className="flex items-center gap-2 rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-4 py-2.5 text-xs text-slate-400 shadow-sm">
              <Loader2 size={13} className="animate-spin" />
              {running ? '正在分析…' : '思考中…'}
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
