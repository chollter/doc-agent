import { useCallback, useEffect, useRef, useState } from 'react';
import { FileUp, Loader2, Play, RotateCcw, ShieldAlert, Upload } from 'lucide-react';
import {
  analysisApi,
  subscribeSteps,
  getErrorMessage,
  type DocumentView,
  type RunDetail,
  type StepEvent,
} from '../api/analysis';
import StepTimeline from '../components/StepTimeline';
import ReportCard from '../components/ReportCard';
import DocumentPanel from '../components/DocumentPanel';
import { durationSeconds } from '../utils/format';

const EXAMPLE_INSTRUCTIONS = [
  '提炼核心内容，给出改进建议',
  '找出文档中的风险点并评估影响',
  '用三句话总结这份文档',
];

const SKILLS = [
  { name: 'document-analysis', label: '文档分析' },
  { name: 'resume-review', label: '简历审查' },
];

const SAMPLE_DOCS = [
  { file: 'resume.pdf', label: '示例简历', skill: 'resume-review', instruction: '提炼这份简历的亮点，并给出针对性的改进建议' },
  { file: 'product-requirements.docx', label: '需求文档', skill: 'document-analysis', instruction: '梳理这份需求文档的核心功能与开放风险' },
  { file: 'tech-spec.md', label: '技术方案', skill: 'document-analysis', instruction: '评估这份技术方案的可行性，指出主要风险与建议' },
];

const ACCEPT = '.pdf,.docx,.md,.txt';

type Phase = 'idle' | 'running' | 'done' | 'failed';

export default function AnalysisPage() {
  const [file, setFile] = useState<File | null>(null);
  const [instruction, setInstruction] = useState(EXAMPLE_INSTRUCTIONS[0]);
  const [skill, setSkill] = useState(SKILLS[0].name);
  const [dragOver, setDragOver] = useState(false);
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState<string | null>(null);
  const [steps, setSteps] = useState<StepEvent[]>([]);
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [doc, setDoc] = useState<DocumentView | null>(null);
  const [highlight, setHighlight] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);

  const closeStreamRef = useRef<(() => void) | null>(null);
  const pollRef = useRef<number | null>(null);
  const startedAtRef = useRef<number>(0);

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
    if (phase !== 'running') return;
    const timer = window.setInterval(() => {
      setElapsed((Date.now() - startedAtRef.current) / 1000);
    }, 200);
    return () => window.clearInterval(timer);
  }, [phase]);

  const mergeStep = useCallback((event: StepEvent) => {
    setSteps((prev) => {
      const idx = prev.findIndex((s) => s.stepId === event.stepId);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = event;
        return next;
      }
      return [...prev, event];
    });
  }, []);

  const refreshDetail = useCallback(async (runId: string) => {
    const d = await analysisApi.getRun(runId);
    setDetail(d);
    if (d.status === 'COMPLETED') {
      setPhase('done');
      cleanup();
    } else if (d.status === 'FAILED') {
      setPhase('failed');
      setError(d.lastError ?? '分析失败');
      cleanup();
    }
  }, [cleanup]);

  const start = async () => {
    if (!file || phase === 'running') return;
    setError(null);
    setSteps([]);
    setDetail(null);
    setHighlight(null);
    setPhase('running');
    setElapsed(0);
    startedAtRef.current = Date.now();
    try {
      const { runId } = await analysisApi.submit(file, instruction, skill);
      analysisApi.getDocument(runId).then(setDoc).catch(() => setDoc(null));
      closeStreamRef.current = subscribeSteps(runId, mergeStep);
      pollRef.current = window.setInterval(() => {
        refreshDetail(runId).catch(() => {/* 轮询失败由下一轮兜底 */});
      }, 2500);
    } catch (ex) {
      setPhase('failed');
      setError(getErrorMessage(ex));
    }
  };

  const loadSample = async (sample: (typeof SAMPLE_DOCS)[number]) => {
    try {
      setError(null);
      const resp = await fetch(`${import.meta.env.PROD ? '' : 'http://localhost:8020'}/samples/${sample.file}`);
      if (!resp.ok) throw new Error(`示例文档加载失败（${resp.status}）`);
      const blob = await resp.blob();
      setFile(new File([blob], sample.file, { type: blob.type }));
      setInstruction(sample.instruction);
      setSkill(sample.skill);
    } catch (ex) {
      setError(getErrorMessage(ex));
    }
  };

  const reset = () => {
    cleanup();
    setPhase('idle');
    setSteps([]);
    setDetail(null);
    setDoc(null);
    setError(null);
  };

  const decideAction = async (approve: boolean) => {
    const action = detail?.pending?.[0];
    if (!action) return;
    try {
      if (approve) {
        await analysisApi.confirmAction(action.id);
      } else {
        await analysisApi.rejectAction(action.id);
      }
    } catch (ex) {
      setError(getErrorMessage(ex));
    }
    // 状态变化由既有轮询自动拉取（工具侧放行后 run 回到 ANALYZING → COMPLETED）
  };

  return (
    <div className="grid h-screen grid-cols-12 gap-4 p-4">
      {/* 左：输入区 */}
      <div className="col-span-3 flex min-w-0 flex-col gap-4 overflow-y-auto">
        <div>
          <h2 className="text-lg font-bold text-slate-800">文档分析</h2>
          <p className="mt-1 text-xs leading-5 text-slate-500">
            上传文档，Agent 以 ReAct 方式自主阅读并按你的要求提炼内容、给出建议
          </p>
        </div>

        <label
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragOver(false);
            const f = e.dataTransfer.files?.[0];
            if (f) setFile(f);
          }}
          className={`flex cursor-pointer flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed p-8 text-center transition-colors ${
            dragOver ? 'border-indigo-400 bg-indigo-50' : 'border-slate-200 bg-white hover:border-indigo-300'
          }`}
        >
          <input
            type="file"
            accept={ACCEPT}
            className="hidden"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          {file ? (
            <>
              <FileUp size={26} className="text-indigo-500" />
              <span className="break-all text-sm font-medium text-slate-700">{file.name}</span>
              <span className="text-xs text-slate-400">{(file.size / 1024).toFixed(0)} KB</span>
            </>
          ) : (
            <>
              <Upload size={26} className="text-slate-300" />
              <span className="text-sm text-slate-500">拖拽文件到此处，或点击选择</span>
              <span className="text-xs text-slate-400">支持 PDF / DOCX / MD / TXT，≤ 10MB</span>
            </>
          )}
        </label>

        <div>
          <div className="mb-1.5 flex items-center justify-between">
            <span className="text-xs font-semibold text-slate-500">没有文件？试试示例</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {SAMPLE_DOCS.map((s) => (
              <button
                key={s.file}
                type="button"
                onClick={() => loadSample(s)}
                className="rounded-full border border-indigo-200 bg-indigo-50/60 px-2.5 py-1 text-xs text-indigo-600 hover:bg-indigo-100"
              >
                {s.label}
              </button>
            ))}
          </div>
        </div>

        <div>
          <div className="mb-1.5 text-xs font-semibold text-slate-500">分析技能</div>
          <div className="flex gap-1.5">
            {SKILLS.map((s) => (
              <button
                key={s.name}
                type="button"
                onClick={() => setSkill(s.name)}
                className={`rounded-full px-3 py-1.5 text-xs transition-colors ${
                  skill === s.name
                    ? 'bg-sky-600 font-semibold text-white'
                    : 'border border-slate-200 text-slate-600 hover:bg-slate-100'
                }`}
              >
                {s.label}
              </button>
            ))}
          </div>
        </div>

        <div>
          <div className="mb-1.5 text-xs font-semibold text-slate-500">分析要求</div>
          <textarea
            value={instruction}
            onChange={(e) => setInstruction(e.target.value)}
            rows={3}
            className="w-full resize-none rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-400"
            placeholder="例如：提炼亮点并给出改进建议"
          />
          <div className="mt-2 flex flex-wrap gap-1.5">
            {EXAMPLE_INSTRUCTIONS.map((text) => (
              <button
                key={text}
                type="button"
                onClick={() => setInstruction(text)}
                className="rounded-full border border-slate-200 px-2.5 py-1 text-xs text-slate-500 hover:border-indigo-300 hover:bg-indigo-50 hover:text-indigo-600"
              >
                {text}
              </button>
            ))}
          </div>
        </div>

        <div className="flex gap-2">
          <button
            type="button"
            onClick={start}
            disabled={!file || phase === 'running'}
            className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-indigo-600 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            {phase === 'running' ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />}
            {phase === 'running' ? `分析中 ${elapsed.toFixed(0)}s` : '开始分析'}
          </button>
          {(phase === 'done' || phase === 'failed') && (
            <button
              type="button"
              onClick={reset}
              className="flex items-center gap-1.5 rounded-xl border border-slate-200 px-3 text-sm text-slate-600 hover:bg-slate-100"
            >
              <RotateCcw size={14} />
              重来
            </button>
          )}
        </div>

        {error && (
          <div className="rounded-xl bg-rose-50 px-3 py-2 text-xs leading-5 text-rose-600">{error}</div>
        )}

        {detail?.status === 'WAIT_HUMAN_CONFIRM' && (detail.pending?.length ?? 0) > 0 && (
          <div className="rounded-xl border border-amber-300 bg-amber-50 p-4">
            <div className="mb-1.5 flex items-center gap-1.5 text-sm font-semibold text-amber-700">
              <ShieldAlert size={15} />
              高危操作待人工确认（DANGER 工具已被门控拦截）
            </div>
            <p className="mb-3 max-h-36 overflow-y-auto whitespace-pre-wrap rounded-lg bg-white/70 p-2.5 text-xs leading-5 text-slate-600">
              {(detail.pending ?? [])[0].payload.slice(0, 500)}
              {(detail.pending ?? [])[0].payload.length > 500 ? '…' : ''}
            </p>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => decideAction(true)}
                className="rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-700"
              >
                确认执行
              </button>
              <button
                type="button"
                onClick={() => decideAction(false)}
                className="rounded-lg border border-amber-300 px-3 py-1.5 text-xs text-amber-700 hover:bg-amber-100"
              >
                拒绝
              </button>
            </div>
          </div>
        )}
      </div>

      {/* 中：Agent 时间线 + 报告 */}
      <div className="col-span-5 flex min-w-0 flex-col gap-4 overflow-y-auto">
        <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="mb-4 flex items-center justify-between">
            <h3 className="text-sm font-bold text-slate-800">Agent 执行过程</h3>
            {detail && (
              <span className="text-xs text-slate-400">
                {durationSeconds(detail.createdAt, detail.finishedAt)}
              </span>
            )}
          </div>
          <StepTimeline steps={steps} />
        </div>
        {detail?.result && (
          <ReportCard
            result={detail.result}
            mode={detail.executionMode}
            onCitation={setHighlight}
          />
        )}
      </div>

      {/* 右：文档面板 */}
      <div className="col-span-4 min-w-0">
        <DocumentPanel doc={doc} highlight={highlight} />
      </div>
    </div>
  );
}
