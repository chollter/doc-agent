import { useCallback, useEffect, useRef, useState } from 'react';
import { FileUp, Loader2, MessageSquare, Play, RotateCcw, ShieldAlert, Upload } from 'lucide-react';
import {
  analysisApi,
  subscribeSteps,
  getErrorMessage,
  type AuditStep,
  type DocumentView,
  type RunDetail,
  type StepEvent,
} from '../api/analysis';
import StepTimeline from '../components/StepTimeline';
import ExecutionChain from '../components/ExecutionChain';
import ReportCard from '../components/ReportCard';
import ResumeReportCard from '../components/ResumeReportCard';
import InterviewChat from '../components/InterviewChat';
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
  const [jobDescription, setJobDescription] = useState('');
  const [targetDirection, setTargetDirection] = useState('');
  const [persona, setPersona] = useState('');
  const [promptVersion, setPromptVersion] = useState('');
  const [optimizationNote, setOptimizationNote] = useState('');
  const [chainSteps, setChainSteps] = useState<AuditStep[]>([]);
  const [interviewMode, setInterviewMode] = useState(false);

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
      // 终态拉取执行链路——出问题时不用去别处挖，现场就在报告旁边
      analysisApi.getAudit(runId).then(setChainSteps).catch(() => setChainSteps([]));
    } else if (d.status === 'FAILED') {
      setPhase('failed');
      setError(d.lastError ?? '分析失败');
      cleanup();
      analysisApi.getAudit(runId).then(setChainSteps).catch(() => setChainSteps([]));
    }
  }, [cleanup]);

  const start = async () => {
    if (!file || phase === 'running') return;
    setError(null);
    setSteps([]);
    setChainSteps([]);
    setDetail(null);
    setHighlight(null);
    setPhase('running');
    setElapsed(0);
    startedAtRef.current = Date.now();
    try {
      const { runId } = await analysisApi.submit(
        file, instruction, skill,
        skill === 'resume-review' && jobDescription.trim() ? jobDescription.trim() : undefined,
        skill === 'resume-review' && !jobDescription.trim() && targetDirection.trim() ? targetDirection.trim() : undefined,
        skill === 'resume-review' && persona ? persona : undefined,
        promptVersion.trim() || undefined,
        optimizationNote.trim() || undefined
      );
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
    setChainSteps([]);
    setDetail(null);
    setDoc(null);
    setError(null);
    setInterviewMode(false);
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

        {skill === 'resume-review' && (
          <div>
            <div className="mb-1.5 flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500">目标岗位 JD（可选）</span>
              {jobDescription && (
                <button
                  type="button"
                  onClick={() => setJobDescription('')}
                  className="text-xs text-slate-400 hover:text-slate-600"
                >
                  清空
                </button>
              )}
            </div>
            <textarea
              value={jobDescription}
              onChange={(e) => setJobDescription(e.target.value)}
              rows={4}
              className="w-full resize-none rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-sky-400"
              placeholder="粘贴岗位 JD，Agent 将逐条对照简历进行匹配分析..."
            />
          </div>
        )}

        {skill === 'resume-review' && !jobDescription.trim() && (
          <div>
            <div className="mb-1.5 flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-500">
                求职方向（广撒网模式，可选）——没有具体 JD 时按方向画像分析
              </span>
              {targetDirection && (
                <button
                  type="button"
                  onClick={() => setTargetDirection('')}
                  className="text-xs text-slate-400 hover:text-slate-600"
                >
                  清空
                </button>
              )}
            </div>
            <input
              value={targetDirection}
              onChange={(e) => setTargetDirection(e.target.value)}
              className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-sky-400"
              placeholder="如：AI 应用开发 / Agent 开发 / LLM 应用"
            />
          </div>
        )}

        {skill === 'resume-review' && (
          <div>
            <span className="mb-1.5 block text-xs font-semibold text-slate-500">
              候选人画像（可选）——影响红旗阈值与建议侧重
            </span>
            <div className="flex gap-1.5">
              {[
                { value: '', label: '自动推断' },
                { value: 'NEW_GRAD', label: '应届/初级' },
                { value: 'SENIOR', label: '资深' },
                { value: 'CAREER_SWITCH', label: '转行' },
              ].map((p) => (
                <button
                  key={p.value}
                  type="button"
                  onClick={() => setPersona(p.value)}
                  className={`rounded-full px-3 py-1 text-xs transition-colors ${
                    persona === p.value
                      ? 'bg-indigo-600 text-white'
                      : 'border border-slate-200 bg-white text-slate-500 hover:border-indigo-300 hover:bg-indigo-50'
                  }`}
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* 校准标注——测出来不准时，改完重跑同一份文件，版本+说明让前后可比 */}
        <details className="rounded-xl border border-slate-200 bg-slate-50/50 px-3 py-2">
          <summary className="cursor-pointer text-xs font-semibold text-slate-500">
            校准标注（可选）——评测/调优迭代时填写
          </summary>
          <div className="mt-2 grid grid-cols-[120px_1fr] items-center gap-2 text-xs">
            <span className="text-slate-500">版本标签</span>
            <input
              value={promptVersion}
              onChange={(e) => setPromptVersion(e.target.value)}
              placeholder="如 resume-v2（对应 prompt 文件的 git 版本）"
              className="rounded-lg border border-slate-200 bg-white px-2 py-1.5 outline-none focus:border-indigo-400"
            />
            <span className="text-slate-500">优化说明</span>
            <input
              value={optimizationNote}
              onChange={(e) => setOptimizationNote(e.target.value)}
              placeholder="这次改了什么、为什么——校准工作台里会显示在这条运行旁边"
              className="rounded-lg border border-slate-200 bg-white px-2 py-1.5 outline-none focus:border-indigo-400"
            />
          </div>
        </details>

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
          <ExecutionChain steps={chainSteps} defaultCollapsed={chainSteps.every((s) => s.status !== 'FAILED')} />
        )}
        {detail?.result && (
          detail.skill === 'resume-review'
            ? <ResumeReportCard result={detail.result} mode={detail.executionMode} onCitation={setHighlight} />
            : <ReportCard result={detail.result} mode={detail.executionMode} onCitation={setHighlight} />
        )}

        {/* 面试模拟入口 */}
        {detail?.skill === 'resume-review' && detail.status === 'COMPLETED' && !interviewMode && (
          <button
            type="button"
            onClick={() => setInterviewMode(true)}
            className="flex items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-sky-300 bg-sky-50/50 py-3 text-sm font-medium text-sky-600 transition-colors hover:border-sky-400 hover:bg-sky-50"
          >
            <MessageSquare size={16} />
            开始面试模拟
          </button>
        )}

        {/* 面试模拟对话 */}
        {interviewMode && detail?.runId && (
          <InterviewChat
            runId={detail.runId}
            onEnd={() => setInterviewMode(false)}
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
