import { FormEvent, useState } from 'react';
import { AlertTriangle, CheckCircle2, FileText, Loader2, Play, Quote, Sparkles } from 'lucide-react';
import { getErrorMessage } from '../api/request';
import { summaryApi, type DocumentSummaryReport, type TaskSection } from '../api/summary';

const examples = [
  { label: '简历优势与不足', instruction: '分析这份简历的优势、不足，并给出求职建议' },
  { label: '论文贡献与局限', instruction: '总结这篇论文的核心贡献、方法和局限' },
  { label: '技术方案评审', instruction: '评估这份技术方案的架构风险、技术取舍和落地建议' },
];

export default function SummaryPage() {
  const [file, setFile] = useState<File | null>(null);
  const [instruction, setInstruction] = useState(examples[0].instruction);
  const [report, setReport] = useState<DocumentSummaryReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file) { setError('请先选择一份 Markdown 或 TXT 资料。'); return; }
    setLoading(true); setError(''); setReport(null);
    try { setReport(await summaryApi.run(file, instruction)); }
    catch (reason) { setError(getErrorMessage(reason)); }
    finally { setLoading(false); }
  }

  return (
    <div className="mx-auto max-w-6xl space-y-8">
      <header className="flex items-start justify-between gap-6">
        <div>
          <p className="mb-3 text-sm font-semibold uppercase tracking-[0.24em] text-primary-600">Task-driven Document Agent</p>
          <h1 className="text-4xl font-bold tracking-tight text-slate-900">把资料交给 Agent</h1>
          <p className="mt-3 max-w-2xl text-base leading-7 text-slate-500">上传资料并告诉 Agent 你想完成什么任务。它会先识别任务、规划维度，再生成匹配的报告章节。</p>
        </div>
        <div className="hidden items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-slate-600 shadow-sm ring-1 ring-slate-100 sm:flex"><Sparkles className="h-4 w-4 text-primary-500" />动态任务规划</div>
      </header>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
        <form onSubmit={handleSubmit} className="rounded-3xl bg-white p-7 shadow-xl shadow-indigo-100/50 ring-1 ring-slate-100">
          <div className="mb-6 flex items-center gap-3"><div className="rounded-2xl bg-primary-50 p-3 text-primary-600"><FileText className="h-6 w-6" /></div><div><h2 className="font-semibold text-slate-900">新建分析任务</h2><p className="text-sm text-slate-400">支持 Markdown / TXT，最大 512KB</p></div></div>
          <label className="mb-2 block text-sm font-semibold text-slate-700" htmlFor="summary-file">资料文件</label>
          <input id="summary-file" type="file" accept=".md,.txt,text/markdown,text/plain" onChange={(event) => setFile(event.target.files?.[0] ?? null)} className="block w-full cursor-pointer rounded-2xl border border-dashed border-primary-200 bg-primary-50/40 p-4 text-sm text-slate-500 file:mr-4 file:rounded-xl file:border-0 file:bg-primary-600 file:px-4 file:py-2 file:font-medium file:text-white hover:border-primary-400" />
          {file && <p className="mt-2 text-xs text-slate-400">已选择：{file.name} · {(file.size / 1024).toFixed(1)}KB</p>}
          <label className="mb-2 mt-6 block text-sm font-semibold text-slate-700" htmlFor="summary-instruction">你希望 Agent 完成什么任务？</label>
          <textarea id="summary-instruction" value={instruction} onChange={(event) => setInstruction(event.target.value)} rows={5} className="w-full resize-none rounded-2xl border border-slate-200 bg-slate-50 p-4 text-sm leading-6 outline-none transition focus:border-primary-400 focus:bg-white focus:ring-4 focus:ring-primary-100" placeholder="例如：分析优势、不足并给出建议" />
          <div className="mt-3 flex flex-wrap gap-2">{examples.map((example) => <button type="button" key={example.label} onClick={() => setInstruction(example.instruction)} className="rounded-full bg-slate-100 px-3 py-1.5 text-xs text-slate-500 transition hover:bg-primary-50 hover:text-primary-600">{example.label}</button>)}</div>
          {error && <div className="mt-5 flex items-start gap-2 rounded-2xl bg-red-50 p-3 text-sm text-red-600"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />{error}</div>}
          <button type="submit" disabled={loading} className="mt-6 flex w-full items-center justify-center gap-2 rounded-2xl bg-primary-600 px-5 py-3.5 font-semibold text-white shadow-lg shadow-primary-500/25 transition hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-60">{loading ? <><Loader2 className="h-5 w-5 animate-spin" />Agent 正在规划与分析…</> : <><Play className="h-5 w-5" />开始执行任务</>}</button>
        </form>
        <section className="min-h-[520px] rounded-3xl bg-white p-7 shadow-xl shadow-indigo-100/50 ring-1 ring-slate-100">
          {!report && !loading && <EmptyState />}
          {loading && <div className="flex h-full min-h-[460px] flex-col items-center justify-center text-center"><Loader2 className="h-10 w-10 animate-spin text-primary-500" /><h2 className="mt-5 font-semibold text-slate-800">Agent 正在识别任务</h2><p className="mt-2 text-sm text-slate-400">先规划分析维度，再生成动态报告…</p></div>}
          {report && <SummaryReport report={report} />}
        </section>
      </div>
    </div>
  );
}

function EmptyState() {
  return <div className="flex h-full min-h-[460px] flex-col items-center justify-center text-center"><div className="mb-5 rounded-3xl bg-gradient-to-br from-primary-50 to-indigo-100 p-5 text-primary-600"><Sparkles className="h-10 w-10" /></div><h2 className="text-xl font-semibold text-slate-800">等待你的第一个任务</h2><p className="mt-2 max-w-sm text-sm leading-6 text-slate-400">不同任务会产生不同的分析计划、报告章节和引用。</p></div>;
}

function SummaryReport({ report }: { report: DocumentSummaryReport }) {
  const list = (items: string[]) => items.length ? <ul className="space-y-2 text-sm leading-6 text-slate-600">{items.map((item, index) => <li key={`${item}-${index}`} className="flex gap-2"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-primary-400" />{item}</li>)}</ul> : <p className="text-sm text-slate-400">暂无内容</p>;
  return <div className="space-y-6">
    <div className="flex items-center justify-between border-b border-slate-100 pb-5"><div><p className="text-xs font-semibold uppercase tracking-wider text-slate-400">{report.taskType || report.skill}</p><h2 className="mt-1 text-2xl font-bold text-slate-900">动态分析报告</h2></div><span className={`rounded-full px-3 py-1 text-xs font-semibold ${report.executionMode === 'FALLBACK' ? 'bg-amber-50 text-amber-600' : 'bg-emerald-50 text-emerald-600'}`}>{report.executionMode}</span></div>
    <div className="rounded-2xl border border-primary-100 bg-primary-50/50 p-4"><p className="text-xs font-semibold uppercase tracking-wider text-primary-600">任务目标</p><p className="mt-1 text-sm leading-6 text-slate-700">{report.goal}</p><p className="mt-3 text-xs font-semibold uppercase tracking-wider text-primary-600">分析维度</p><div className="mt-2 flex flex-wrap gap-2">{report.dimensions.map((dimension) => <span key={dimension} className="rounded-full bg-white px-3 py-1 text-xs text-slate-600">{dimension}</span>)}</div></div>
    <div><h3 className="mb-2 font-semibold text-slate-800">摘要</h3><p className="rounded-2xl bg-slate-50 p-4 text-sm leading-7 text-slate-600">{report.summary}</p></div>
    {report.sections?.length ? <div><h3 className="mb-3 font-semibold text-slate-800">动态报告章节</h3><div className="space-y-4">{report.sections.map((section, index) => <SectionCard key={`${section.title}-${index}`} section={section} />)}</div></div> : null}
    <div className="grid gap-5 sm:grid-cols-2"><div><h3 className="mb-2 flex items-center gap-2 font-semibold text-slate-800"><AlertTriangle className="h-4 w-4 text-amber-500" />风险</h3>{list(report.risks)}</div><div><h3 className="mb-2 flex items-center gap-2 font-semibold text-slate-800"><CheckCircle2 className="h-4 w-4 text-emerald-500" />待办</h3>{list(report.todos)}</div></div>
    <div><h3 className="mb-2 flex items-center gap-2 font-semibold text-slate-800"><Quote className="h-4 w-4 text-primary-500" />引用证据</h3>{list(report.citations)}</div>
    <div className="border-t border-slate-100 pt-5"><h3 className="mb-3 font-semibold text-slate-800">Agent 执行过程</h3><div className="space-y-3">{report.steps.map((step, index) => <div key={`${step.name}-${index}`} className="flex items-start gap-3"><div className="mt-0.5 rounded-full bg-emerald-50 p-1 text-emerald-600"><CheckCircle2 className="h-4 w-4" /></div><div><p className="text-sm font-medium text-slate-700">{step.name} <span className="ml-2 text-xs font-normal text-slate-400">{step.status}</span></p><p className="text-xs text-slate-400">{step.detail}</p></div></div>)}</div></div>
  </div>;
}

function SectionCard({ section }: { section: TaskSection }) {
  return <article className="rounded-2xl border border-slate-100 bg-white p-4 shadow-sm"><h4 className="font-semibold text-slate-800">{section.title}</h4>{section.purpose && <p className="mt-1 text-xs text-slate-400">{section.purpose}</p>}<ul className="mt-3 space-y-2 text-sm leading-6 text-slate-600">{section.findings.map((finding, index) => <li key={`${finding}-${index}`} className="flex gap-2"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-primary-400" />{finding}</li>)}</ul>{section.uncertainties.length > 0 && <p className="mt-3 rounded-xl bg-amber-50 p-3 text-xs leading-5 text-amber-700">不确定项：{section.uncertainties.join('；')}</p>}{section.citations.length > 0 && <p className="mt-3 text-xs text-primary-600">引用：{section.citations.join('；')}</p>}</article>;
}
