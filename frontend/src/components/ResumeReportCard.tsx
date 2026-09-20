import { AlertTriangle, Bookmark, Briefcase, ClipboardList, GraduationCap, Lightbulb, ShieldAlert, Sparkles, Target, TrendingUp, User } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { AnalysisResult, Evaluation, ResumeProfile, SkillMatrix, FunnelVerdict } from '../api/analysis';

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

/** 候选人画像条 */
function ProfileBar({ profile }: { profile: ResumeProfile }) {
  const allSkills = flattenSkillMatrix(profile.skillMatrix);
  return (
    <div className="rounded-xl bg-gradient-to-r from-indigo-50 via-sky-50 to-violet-50 p-4">
      <div className="mb-3 flex flex-wrap items-center gap-3">
        {profile.name && (
          <div className="flex items-center gap-1.5 text-base font-bold text-slate-800">
            <User size={16} className="text-indigo-500" />
            {profile.name}
          </div>
        )}
        {profile.yearsOfExperience > 0 && (
          <span className="rounded-full bg-white/80 px-2.5 py-0.5 text-xs font-medium text-slate-600">
            {profile.yearsOfExperience} 年经验
          </span>
        )}
        {profile.currentRole && (
          <span className="flex items-center gap-1 rounded-full bg-white/80 px-2.5 py-0.5 text-xs font-medium text-slate-600">
            <Briefcase size={12} className="text-sky-500" />
            {profile.currentRole}
          </span>
        )}
      </div>

      {/* 技能标签云 */}
      {allSkills.length > 0 && (
        <div className="mb-3 flex flex-wrap gap-1.5">
          {allSkills.map((skill, i) => (
            <span key={i} className="rounded-full border border-indigo-200 bg-white px-2 py-0.5 text-xs text-indigo-600">
              {skill}
            </span>
          ))}
        </div>
      )}

      {/* 工作经历时间线 */}
      {profile.workTimeline.length > 0 && (
        <div className="space-y-2">
          <div className="text-xs font-semibold text-slate-500">工作经历</div>
          {profile.workTimeline.map((entry, i) => (
            <div key={i} className="rounded-lg bg-white/70 px-3 py-2 text-xs">
              <div className="flex items-center gap-2 font-medium text-slate-700">
                <span>{entry.company}</span>
                {entry.role && <span className="text-slate-400">·</span>}
                {entry.role && <span className="text-slate-600">{entry.role}</span>}
                {entry.period && <span className="ml-auto text-slate-400">{entry.period}</span>}
              </div>
              {entry.highlights.length > 0 && (
                <ul className="mt-1 space-y-0.5 text-slate-500">
                  {entry.highlights.slice(0, 3).map((h, j) => (
                    <li key={j} className="line-clamp-1">• {h}</li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 教育背景 */}
      {profile.education.length > 0 && (
        <div className="mt-2 space-y-1">
          <div className="text-xs font-semibold text-slate-500">教育背景</div>
          {profile.education.map((edu, i) => (
            <div key={i} className="flex items-center gap-2 rounded-lg bg-white/70 px-3 py-1.5 text-xs text-slate-600">
              <GraduationCap size={12} className="text-emerald-500" />
              <span>{edu.school}</span>
              {edu.major && <span className="text-slate-400">·</span>}
              {edu.major && <span>{edu.major}</span>}
              {edu.degree && <span className="text-slate-400">·</span>}
              {edu.degree && <span>{edu.degree}</span>}
              {edu.year && <span className="ml-auto text-slate-400">{edu.year}</span>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function flattenSkillMatrix(sm: SkillMatrix | undefined): string[] {
  if (!sm) return [];
  return [...sm.languages, ...sm.frameworks, ...sm.infrastructure, ...sm.databases, ...sm.others];
}

/** 一句话裁决：从裁决字段纯代码推导（不依赖 LLM，不漂移），结论区第一行直接给判断。 */
function verdictLine(verdict: FunnelVerdict): { text: string; alert: boolean } {
  const high = (verdict.redFlags ?? []).filter((f) => f.severity === 'HIGH');
  if (high.length > 0) {
    const first = high[0].message.replace(/（.*$/, '');
    return { text: `有 ${high.length} 项一票否决需先处理：${first}`, alert: true };
  }
  const parts: string[] = [];
  if (verdict.strength) {
    const bandText = verdict.strength.band === 'STRONG' ? '强'
      : verdict.strength.band === 'MIXED' ? '混合' : '弱';
    parts.push(`内容强度${bandText}`);
  }
  if (verdict.presentation) {
    parts.push(`表达 ${verdict.presentation.score} 分（${verdict.presentation.band} 档）`);
  }
  if (verdict.matchMode === 'NONE' && verdict.recommendedDirections && verdict.recommendedDirections.length > 0) {
    const best = verdict.recommendedDirections.find((d) => d.tier === 'BEST_FIT') ?? verdict.recommendedDirections[0];
    parts.push(`最适方向「${best.direction}」`);
  }
  return { text: `无一票否决项；${parts.join(' · ')}`, alert: false };
}

/** 定性评价（v6）：总评 + 五维评语——结论区首屏直给评价；优缺点不再单列，避免与分维评语重复 */
function EvaluationSection({ evaluation }: { evaluation: Evaluation }) {
  const dimensions = evaluation.dimensions ?? [];
  return (
    <section className="space-y-4">
      {evaluation.overall && (
        <div className="prose prose-sm prose-slate max-w-none [&_p]:my-1.5 [&_ul]:my-1 [&_ol]:my-1 [&_li]:my-0.5 [&_blockquote]:my-1.5 [&_strong]:text-slate-900">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{evaluation.overall}</ReactMarkdown>
        </div>
      )}
      {dimensions.length > 0 && (
        <div className="space-y-3 border-t border-slate-200 pt-4">
          {dimensions.map((d, i) => (
            <div key={i} className="space-y-1 text-sm leading-6">
              <div>
                <span className="mr-2 font-semibold text-slate-900">{d.dimension}</span>
                <span className="text-slate-700">{d.level ? `【${d.level}】` : ''}{d.comment}</span>
              </div>
              {(d.issueType && d.issueType !== 'NONE') && (
                <div className="text-xs text-slate-500">问题类型：{d.issueType}</div>
              )}
              {d.evidence && d.evidence.length > 0 && (
                <div className="text-xs text-slate-500">证据：{d.evidence.join('；')}</div>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/** P12 漏斗式结论——按"会死在哪一关"的顺序渲染 */
function FunnelVerdictSection({ verdict, onCitation }: {
  verdict: FunnelVerdict;
  onCitation?: (sectionId: string) => void;
}) {
  const line = verdictLine(verdict);
  const evaluation = verdict.evaluation ?? null;
  return (
    <div className="resume-plain-output space-y-5">
      {/* 一票否决警报最优先；否则定性评价置顶（v6：首屏是评价而非指标标签） */}
      {line.alert && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-700">
          {line.text}
        </div>
      )}

      {evaluation && <EvaluationSection evaluation={evaluation} />}

      {/* 确定性指标行：无评价时（历史 run）仍担当首屏；有评价时降为辅助行 */}
      {!line.alert && (evaluation ? (
        <div className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">{line.text}</div>
      ) : (
        <div className="rounded-xl border border-emerald-100 bg-emerald-50/70 px-4 py-3 text-sm font-semibold text-slate-700">
          {line.text}
        </div>
      ))}

      {verdict.analysisDegraded && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 p-2.5 text-xs leading-5 text-amber-700">
          ⚠ 实体抽取降级：红旗仅粗查、候选人画像缺失；重试可获得完整分析。
        </div>
      )}

      {/* 第一关：红旗筛查（一票否决层，置顶） */}
      {verdict.redFlags && verdict.redFlags.length > 0 && (
        <div>
          <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-rose-600">
            <ShieldAlert size={15} />
            红旗筛查（先解决这些，再谈其他）
          </div>
          <div className="space-y-2">
            {verdict.redFlags.map((flag, i) => {
              const color = flag.severity === 'HIGH'
                ? 'border-rose-300 bg-rose-50 text-rose-700'
                : flag.severity === 'MEDIUM'
                  ? 'border-amber-300 bg-amber-50 text-amber-700'
                  : 'border-slate-200 bg-slate-50 text-slate-600';
              const label = flag.severity === 'HIGH' ? '高危'
                : flag.severity === 'MEDIUM' ? '中风险' : '提示';
              return (
                <div key={i} className={`rounded-lg border px-3 py-2 text-xs leading-5 ${color}`}>
                  <span className="mr-1.5 font-semibold">{label}</span>
                  {flag.message}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* 第二关：岗位匹配（JD 对照 / 方向画像广撒网），默认折叠 */}
      {verdict.matchMode === 'DIRECTION' && (
        <details className="group rounded-xl border border-sky-100 bg-sky-50/50 p-4" open>
          <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-semibold text-sky-700">
            <Target size={15} />
            定位（方向画像：{verdict.archetypeId}）
            <span className="ml-auto text-xs font-normal text-slate-400 group-open:hidden">展开</span>
            <span className="ml-auto hidden text-xs font-normal text-slate-400 group-open:inline">收起</span>
          </summary>
          <div className="mt-3">

          {verdict.positioning && (
            <div className={`rounded-lg px-3 py-2 text-xs leading-5 ${verdict.positioning.anchored ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700'}`}>
              {verdict.positioning.anchored
                ? `定位清晰：${verdict.positioning.currentAnchor ?? ''}`
                : `定位模糊：当前锚定「${verdict.positioning.currentAnchor ?? '无'}」，建议改为「${verdict.positioning.suggestedAnchor ?? ''}」`}
              {verdict.positioning.comment && <div className="mt-0.5 opacity-80">{verdict.positioning.comment}</div>}
            </div>
          )}
          </div>
        </details>
      )}

      {/* 无 JD：定位 + 适合方向（锚定简历自身证据，稳妥/跳一跳由后端按证据强度判定） */}
      {verdict.matchMode === 'NONE'
        && (verdict.positioning || (verdict.recommendedDirections?.length ?? 0) > 0) && (
        <details className="group rounded-xl border border-emerald-100 bg-emerald-50/50 p-4" open>
          <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-semibold text-emerald-700">
            <Lightbulb size={15} />
            定位与适合方向（未提供 JD，基于简历已证明的能力）
            <span className="ml-auto text-xs font-normal text-slate-400 group-open:hidden">展开</span>
            <span className="ml-auto hidden text-xs font-normal text-slate-400 group-open:inline">收起</span>
          </summary>
          <div className="mt-3 space-y-3">
            {verdict.positioning && (
              <div className={`rounded-lg px-3 py-2 text-xs leading-5 ${verdict.positioning.anchored ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700'}`}>
                {verdict.positioning.anchored
                  ? `定位清晰：${verdict.positioning.currentAnchor ?? ''}`
                  : `定位模糊：当前锚定「${verdict.positioning.currentAnchor ?? '无'}」，建议改为「${verdict.positioning.suggestedAnchor ?? ''}」`}
                {verdict.positioning.comment && <div className="mt-0.5 opacity-80">{verdict.positioning.comment}</div>}
              </div>
            )}
            {verdict.recommendedDirections && verdict.recommendedDirections.length > 0 && (
              <div className="space-y-2">
                {verdict.recommendedDirections.map((d, i) => (
                  <div key={i} className="rounded-lg bg-white/80 px-3 py-2 text-xs">
                    <div className="flex items-start gap-2">
                      <span className="font-semibold text-slate-700">{d.direction}</span>
                      <span className={`ml-auto shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                        d.tier === 'BEST_FIT' ? 'bg-emerald-100 text-emerald-700' : 'bg-sky-100 text-sky-700'}`}>
                        {d.tier === 'BEST_FIT' ? '稳妥' : '跳一跳'}
                      </span>
                    </div>
                    {d.evidence && d.evidence.length > 0 && (
                      <div className="mt-1 space-y-0.5">
                        {d.evidence.map((ev, j) => (
                          <button
                            key={j}
                            type="button"
                            className="block text-left text-[11px] text-emerald-600 hover:underline"
                            onClick={() => d.sectionId && onCitation?.(d.sectionId)}
                          >
                            证据：{ev}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </details>
      )}

      {/* 第三关：内容强度，默认折叠 */}
      {verdict.strength && verdict.strength.entryCount > 0 && (
        <details className="group rounded-xl border border-violet-100 bg-violet-50/50 p-4">
          <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-semibold text-violet-700">
            <TrendingUp size={15} />
            内容强度（成就的证据质量）
            <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
              verdict.strength.band === 'STRONG' ? 'bg-emerald-100 text-emerald-700'
                : verdict.strength.band === 'MIXED' ? 'bg-amber-100 text-amber-700'
                : 'bg-rose-100 text-rose-700'}`}>
              {verdict.strength.band === 'STRONG' ? '强' : verdict.strength.band === 'MIXED' ? '混合' : '弱'}
            </span>
          </summary>
          <div className="mt-3">
          <div className="mb-3 grid grid-cols-4 gap-2 text-center text-xs">
            <div className="rounded-lg bg-white/80 py-2">
              <div className="font-bold text-slate-700">{Math.round(verdict.strength.resultRate * 100)}%</div>
              <div className="text-[11px] text-slate-400">有结果佐证</div>
            </div>
            <div className="rounded-lg bg-white/80 py-2">
              <div className="font-bold text-slate-700">{Math.round(verdict.strength.strongResultRate * 100)}%</div>
              <div className="text-[11px] text-slate-400">项目/业务级结果</div>
            </div>
            <div className="rounded-lg bg-white/80 py-2">
              <div className="font-bold text-slate-700">{Math.round(verdict.strength.ownerRate * 100)}%</div>
              <div className="text-[11px] text-slate-400">主导/负责归因</div>
            </div>
            <div className="rounded-lg bg-white/80 py-2">
              <div className="font-bold text-slate-700">{verdict.strength.entryCount}</div>
              <div className="text-[11px] text-slate-400">评估经历数</div>
            </div>
          </div>
          </div>
        </details>
      )}

      {/* 第四关：表达质量，默认折叠 */}
      {verdict.presentation && (
        <details className="group rounded-xl border border-indigo-100 bg-indigo-50/50 p-4">
          <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-semibold text-indigo-700">
            <Sparkles size={15} />
            表达质量
            <span className="rounded-full bg-white/80 px-2 py-0.5 text-xs font-semibold text-slate-600">
              {verdict.presentation.score} 分 · {verdict.presentation.band} 档
            </span>
          </summary>
          <div className="mt-2 space-y-1.5">
            {verdict.presentation.issues.map((issue, i) => (
              <div key={i} className="rounded-lg bg-white/80 px-3 py-1.5 text-xs text-slate-600">{issue}</div>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

/** 简历专属分析报告 */
export default function ResumeReportCard({ result, mode, onCitation }: {
  result: AnalysisResult;
  mode: string | null | undefined;
  onCitation?: (sectionId: string) => void;
}) {
  const hasProfile = result.profile != null;

  return (
    <div className="space-y-6 border border-slate-200 bg-white p-6 text-slate-900">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-base font-bold text-slate-800">简历分析报告</h3>
        <ModeBadge mode={mode} />
      </div>

      {/* 候选人画像 */}
      {hasProfile && <ProfileBar profile={result.profile!} />}

      {/* P12 漏斗式结论（新主结果，按"会死在哪一关"排序） */}
      {result.funnelVerdict && <FunnelVerdictSection verdict={result.funnelVerdict} onCitation={onCitation} />}

      {/* 候选人画像（LLM 总结；判断在结论区顶部的一句话裁决，不再混在一起） */}
      {/* evaluation.overall 与 summary 是同一裁决，避免重复渲染。 */}
      {!result.funnelVerdict?.evaluation && result.summary && (
        <section>
          <h4 className="mb-2 text-sm font-semibold text-slate-900">总体评价</h4>
          <p className="text-sm leading-7 text-slate-800">{result.summary}</p>
        </section>
      )}

      {/* 降级到通用展示：如果没有深度分析数据，回退到 keyPoints/risks */}
      {!hasProfile && !result.funnelVerdict && (
        <>
          {result.keyPoints?.length > 0 && (
            <div>
              <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-indigo-600">
                <Lightbulb size={15} />
                核心亮点
              </div>
              <ul className="space-y-1.5">
                {result.keyPoints.map((item, i) => (
                  <li key={i} className="rounded-lg bg-slate-50 px-3 py-2 text-sm leading-6 text-slate-700">{item}</li>
                ))}
              </ul>
            </div>
          )}
          {result.risks?.length > 0 && (
            <div>
              <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-rose-600">
                <AlertTriangle size={15} />
                风险与问题
              </div>
              <ul className="space-y-1.5">
                {result.risks.map((item, i) => (
                  <li key={i} className="rounded-lg bg-slate-50 px-3 py-2 text-sm leading-6 text-slate-700">{item}</li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}

      {/* 引用证据 */}
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
