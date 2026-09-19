import { AlertTriangle, Award, Bookmark, Briefcase, CheckCircle, ClipboardList, GraduationCap, HelpCircle, Lightbulb, ShieldAlert, Sparkles, Target, TrendingUp, User } from 'lucide-react';
import type { AnalysisResult, EnhancedKeyPoint, EnhancedRisk, Evaluation, ResumeProfile, QualityScore, SkillMatrix, FunnelVerdict, LeverageCard as LeverageCardType, ResumeDiagnosis, ResumeProjectFact, ResumeFact } from '../api/analysis';
import { AlignmentMatrix, type AlignmentEntry } from './AlignmentMatrix';

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

/** 质量评分卡 */
function QualityScoreCard({ score }: { score: QualityScore }) {
  const dimLabels: Record<string, string> = {
    quantification: '量化程度',
    completeness: '信息完整度',
    clarity: '表述清晰度',
    credibility: '可信度',
    professionalism: '专业性',
  };

  const dimColor = (val: number) => {
    if (val >= 80) return 'bg-emerald-500';
    if (val >= 60) return 'bg-sky-500';
    if (val >= 40) return 'bg-amber-500';
    return 'bg-rose-500';
  };

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-3 flex items-center gap-2">
        <TrendingUp size={15} className="text-indigo-500" />
        <span className="text-sm font-semibold text-slate-700">质量评分</span>
        <span className="ml-auto text-2xl font-bold text-indigo-600">{score.overall}</span>
        <span className="text-xs text-slate-400">/ 100</span>
      </div>
      <div className="space-y-2">
        {Object.entries(score.dimensions).map(([key, val]) => (
          <div key={key} className="flex items-center gap-2">
            <span className="w-20 text-xs text-slate-500">{dimLabels[key] ?? key}</span>
            <div className="h-2 flex-1 overflow-hidden rounded-full bg-slate-100">
              <div className={`h-full rounded-full transition-all ${dimColor(val)}`} style={{ width: `${val}%` }} />
            </div>
            <span className="w-8 text-right text-xs font-medium text-slate-600">{val}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** 增强亮点 */
function EnhancedKeyPointsSection({ keyPoints, onCitation }: {
  keyPoints: EnhancedKeyPoint[];
  onCitation?: (sectionId: string) => void;
}) {
  if (!keyPoints || keyPoints.length === 0) return null;
  return (
    <div>
      <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-indigo-600">
        <Award size={15} />
        核心亮点
      </div>
      <div className="space-y-2">
        {keyPoints.map((kp, i) => (
          <div key={i} className="rounded-lg bg-indigo-50/60 p-3">
            <div className="text-sm font-medium text-slate-700">{kp.point}</div>
            {kp.evidence && (
              <p className="mt-1 text-xs leading-5 text-slate-500">
                <span className="font-semibold text-slate-600">证据：</span>{kp.evidence}
              </p>
            )}
            {kp.interviewValue && (
              <p className="mt-1 text-xs leading-5 text-indigo-500">
                <span className="font-semibold">面试官追问：</span>{kp.interviewValue}
              </p>
            )}
            {kp.sectionId && (
              <button
                type="button"
                onClick={() => onCitation?.(kp.sectionId!)}
                className="mt-1 text-xs text-indigo-400 hover:underline"
              >
                定位原文 →
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

/** 增强风险 */
function EnhancedRisksSection({ risks, onCitation }: {
  risks: EnhancedRisk[];
  onCitation?: (sectionId: string) => void;
}) {
  if (!risks || risks.length === 0) return null;
  return (
    <div>
      <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-rose-600">
        <AlertTriangle size={15} />
        风险与面试官挑战角度
      </div>
      <div className="space-y-2">
        {risks.map((r, i) => (
          <div key={i} className="rounded-lg bg-rose-50/60 p-3">
            <div className="text-sm font-medium text-slate-700">{r.risk}</div>
            {r.detail && (
              <p className="mt-1 text-xs leading-5 text-slate-500">{r.detail}</p>
            )}
            {r.challengeAngle && (
              <p className="mt-1 text-xs leading-5 text-rose-500">
                <span className="font-semibold">面试官挑战：</span>{r.challengeAngle}
              </p>
            )}
            {r.sectionId && (
              <button
                type="button"
                onClick={() => onCitation?.(r.sectionId!)}
                className="mt-1 text-xs text-rose-400 hover:underline"
              >
                定位原文 →
              </button>
            )}
          </div>
        ))}
      </div>
    </div>
  );
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
  if (verdict.matchMode === 'DIRECTION' && verdict.variantFit && verdict.variantFit.length > 0) {
    const best = verdict.variantFit.find((v) => v.fit === 'HIGH') ?? verdict.variantFit[0];
    parts.push(`最适方向「${best.name}」`);
  } else if (verdict.matchMode === 'JD' && verdict.mustHaveCoverage && verdict.mustHaveCoverage.length > 0) {
    const met = verdict.mustHaveCoverage.filter((c) => c.status === 'MET').length;
    parts.push(`共性要求满足 ${met}/${verdict.mustHaveCoverage.length}`);
  }
  return { text: `无一票否决项；${parts.join(' · ')}`, alert: false };
}

/** 定性评价（v6）：总评 + 优缺点 + 五维评语——结论区首屏直给评价，不再只有指标标签 */
function EvaluationSection({ evaluation }: { evaluation: Evaluation }) {
  const strengths = evaluation.strengths ?? [];
  const weaknesses = evaluation.weaknesses ?? [];
  const dimensions = evaluation.dimensions ?? [];
  return (
    <div className="space-y-3 rounded-xl border border-slate-200 bg-white p-4">
      {evaluation.overall && (
        <div className="text-sm font-semibold leading-7 text-slate-800">{evaluation.overall}</div>
      )}
      {(strengths.length > 0 || weaknesses.length > 0) && (
        <div className="grid gap-2 md:grid-cols-2">
          {strengths.length > 0 && (
            <div className="rounded-lg bg-emerald-50/70 p-3">
              <div className="mb-1.5 flex items-center gap-1 text-xs font-semibold text-emerald-700">
                <CheckCircle size={13} /> 强项
              </div>
              <ul className="space-y-1 text-xs leading-5 text-slate-700">
                {strengths.map((s, i) => <li key={i}>· {s}</li>)}
              </ul>
            </div>
          )}
          {weaknesses.length > 0 && (
            <div className="rounded-lg bg-amber-50/70 p-3">
              <div className="mb-1.5 flex items-center gap-1 text-xs font-semibold text-amber-700">
                <AlertTriangle size={13} /> 需注意
              </div>
              <ul className="space-y-1 text-xs leading-5 text-slate-700">
                {weaknesses.map((s, i) => <li key={i}>· {s}</li>)}
              </ul>
            </div>
          )}
        </div>
      )}
      {dimensions.length > 0 && (
        <div className="space-y-1 rounded-lg bg-slate-50/80 p-3">
          {dimensions.map((d, i) => (
            <div key={i} className="space-y-0.5 text-xs leading-5">
              <div className="flex items-start gap-2">
                <span className="w-32 shrink-0 font-medium text-slate-500">{d.dimension}</span>
                <span className="text-slate-600">{d.level ? `【${d.level}】` : ''}{d.comment}</span>
              </div>
              {(d.issueType && d.issueType !== 'NONE') && (
                <div className="pl-32 text-[11px] text-amber-600">问题类型：{d.issueType}</div>
              )}
              {d.evidence && d.evidence.length > 0 && (
                <div className="pl-32 text-[11px] text-slate-400">证据：{d.evidence.join('；')}</div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ProjectFactsSection({ projects, onCitation }: {
  projects: ResumeProjectFact[];
  onCitation?: (sectionId: string) => void;
}) {
  if (!projects.length) return null;
  const renderFact = (label: string, fact: ResumeFact | null) => {
    if (!fact || !fact.value) return null;
    return <div className="text-xs leading-5 text-slate-600"><span className="font-medium text-slate-500">{label}：</span>{fact.value}</div>;
  };
  const renderList = (label: string, facts: ResumeFact[]) => {
    const values = facts.filter(f => f?.value).map(f => f.value).join('、');
    return values ? <div className="text-xs leading-5 text-slate-600"><span className="font-medium text-slate-500">{label}：</span>{values}</div> : null;
  };
  return (
    <div>
      <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-sky-600">
        <Target size={15} />
        项目事实与证据
      </div>
      <div className="space-y-2">
        {projects.map((project, i) => (
          <div key={project.projectId ?? i} className="rounded-lg border border-sky-100 bg-sky-50/50 p-3">
            <div className="mb-1 flex items-center justify-between text-xs font-semibold text-slate-700">
              <span>{project.projectId ?? `项目 ${i + 1}`}</span>
              {project.sectionId && <button type="button" onClick={() => onCitation?.(project.sectionId!)} className="text-sky-500 hover:underline">定位原文</button>}
            </div>
            {renderFact('背景', project.context)}
            {renderFact('问题', project.problem)}
            {renderList('职责', project.responsibilities)}
            {renderList('技术', project.technologies)}
            {renderFact('AI 链路', project.aiPipeline)}
            {renderList('决策', project.decisions)}
            {renderFact('结果', project.results)}
            {renderFact('规模', project.scale)}
            {renderFact('上线', project.deployment)}
            <div className="mt-1 text-[11px] text-amber-600">
              缺失字段不会被当作事实，需补充真实信息后再改写。
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function DiagnosisSection({ diagnoses, onCitation }: {
  diagnoses: ResumeDiagnosis[];
  onCitation?: (sectionId: string) => void;
}) {
  if (!diagnoses || diagnoses.length === 0) return null;
  const severityClass: Record<string, string> = {
    HIGH: 'border-rose-200 bg-rose-50/70',
    MEDIUM: 'border-amber-200 bg-amber-50/70',
    LOW: 'border-slate-200 bg-slate-50/80',
  };
  const severityLabel: Record<string, string> = { HIGH: '优先处理', MEDIUM: '建议处理', LOW: '可优化' };
  const levelLabel: Record<string, string> = {
    L0_KEYWORD: 'L0 关键词', L1_ACTIVITY: 'L1 做过什么', L2_METHOD: 'L2 怎么做',
    L3_RESULT: 'L3 有结果', L4_TRADE_OFF: 'L4 有权衡',
  };
  return (
    <section>
      <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-amber-700">
        <Target size={15} /> 简历问题诊断与补强方向
      </div>
      <div className="space-y-3">
        {diagnoses.map((d, i) => (
          <div key={i} className={`rounded-xl border p-3 ${severityClass[d.severity] ?? severityClass.MEDIUM}`}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded-full bg-white/80 px-2 py-0.5 text-[11px] font-semibold text-amber-700">
                {severityLabel[d.severity] ?? d.severity}
              </span>
              {d.evidenceLevel && <span className="text-[11px] font-medium text-slate-500">{levelLabel[d.evidenceLevel] ?? d.evidenceLevel}</span>}
              <span className="text-sm font-semibold text-slate-800">{d.target}</span>
            </div>
            {d.claim && <div className="mt-2 border-l-2 border-amber-300 pl-2 text-xs leading-5 text-slate-600">原文：{d.claim}</div>}
            {d.problemType && <div className="mt-2 text-xs font-medium text-rose-700">问题：{d.problemType}</div>}
            {d.whyItHurts && <p className="mt-1 text-xs leading-5 text-slate-600">影响：{d.whyItHurts}</p>}
            {d.missingFacts?.length > 0 && (
              <div className="mt-2 text-xs leading-5 text-slate-600">
                <span className="font-semibold text-slate-700">缺少事实：</span>{d.missingFacts.join('；')}
              </div>
            )}
            {d.strengtheningDirection && (
              <div className="mt-2 text-xs leading-5 text-emerald-700">
                <span className="font-semibold">补强方向：</span>{d.strengtheningDirection}
              </div>
            )}
            {d.interviewQuestion && (
              <div className="mt-2 rounded-lg bg-white/70 px-2.5 py-1.5 text-xs leading-5 text-slate-600">
                <span className="font-semibold text-slate-700">面试验证：</span>{d.interviewQuestion}
              </div>
            )}
            {d.sectionId && (
              <button type="button" onClick={() => onCitation?.(d.sectionId!)} className="mt-2 text-xs text-amber-700 hover:underline">
                定位原文 →
              </button>
            )}
          </div>
        ))}
      </div>
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
    <div className="space-y-4">
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

      {/* 落地性校验（程序标记）：建议中的编造数字/失锚引文在此暴露 */}
      {verdict.groundingFindings && verdict.groundingFindings.length > 0 && (
        <div className="rounded-xl border border-rose-200 bg-rose-50/70 p-3">
          <div className="mb-1.5 text-xs font-semibold text-rose-600">
            落地性校验：{verdict.groundingFindings.length} 处建议未通过程序校验（数字无出处 / 引文失锚）
          </div>
          <ul className="space-y-1 text-[11px] leading-5 text-rose-500">
            {verdict.groundingFindings.map((f, i) => (
              <li key={i}>· {f.detail}</li>
            ))}
          </ul>
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
            方向匹配（广撒网模式：{verdict.archetypeId}）
            <span className="ml-auto text-xs font-normal text-slate-400 group-open:hidden">展开</span>
            <span className="ml-auto hidden text-xs font-normal text-slate-400 group-open:inline">收起</span>
          </summary>
          <div className="mt-3">

          {/* 新增：对齐矩阵优先展示 */}
          {verdict.requirementVerdicts && verdict.requirementVerdicts.length > 0 && (
            <div className="mb-3">
              <AlignmentMatrix entries={verdict.requirementVerdicts.map(rv => ({
                requirementId: rv.requirementId,
                requirement: rv.requirement,
                status: rv.status === 'MET' ? 'FULL' : rv.status === 'PARTIAL' ? 'PARTIAL' : 'MISSING',
                evidence: rv.supportingEvidence.map((ev, idx) => ({
                  claim: rv.claim || ev,
                  sectionId: rv.sectionIds[idx] || rv.sectionIds[0] || '',
                  evidenceLevel: rv.evidenceLevel,
                })),
                gap: rv.reason,
                fix: rv.fix ? {
                  type: rv.fix.type,
                  before: rv.fix.before,
                  after: rv.fix.after,
                  roiScore: rv.fix.roiScore,
                  reason: rv.fix.reason,
                  effort: rv.fix.effort,
                } : undefined,
              } as AlignmentEntry))} />
            </div>
          )}

          {verdict.mustHaveCoverage && verdict.mustHaveCoverage.length > 0 && (
            <div className="mb-3">
              <div className="mb-1.5 text-xs font-semibold text-slate-500">共性要求（缺失时广撒网救不了）</div>
              <div className="space-y-1.5">
                {verdict.mustHaveCoverage.map((c, i) => {
                  const badge = c.status === 'MET'
                    ? 'bg-emerald-100 text-emerald-700'
                    : c.status === 'PARTIAL'
                      ? 'bg-amber-100 text-amber-700'
                      : 'bg-rose-100 text-rose-700';
                  return (
                    <div key={i} className="rounded-lg bg-white/80 px-3 py-2 text-xs">
                      <div className="flex items-start gap-2">
                        <span className="font-medium text-slate-700">{c.requirement}</span>
                        <span className={`ml-auto shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${badge}`}>
                          {c.status === 'MET' ? '满足' : c.status === 'PARTIAL' ? '部分' : '缺失'}
                        </span>
                      </div>
                      {c.evidence && (
                        <button
                          type="button"
                          className="mt-1 text-left text-[11px] text-sky-600 hover:underline"
                          onClick={() => c.sectionId && onCitation?.(c.sectionId)}
                        >
                          证据：{c.evidence}
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {verdict.variantFit && verdict.variantFit.length > 0 && (
            <div className="mb-3">
              <div className="mb-1.5 text-xs font-semibold text-slate-500">子方向适配（你最适合投哪类）</div>
              <div className="flex flex-wrap gap-1.5">
                {verdict.variantFit.map((v, i) => {
                  const badge = v.fit === 'HIGH'
                    ? 'bg-emerald-100 text-emerald-700'
                    : v.fit === 'MEDIUM'
                      ? 'bg-sky-100 text-sky-700'
                      : 'bg-slate-100 text-slate-500';
                  return (
                    <span key={i} className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${badge}`} title={v.reason}>
                      {v.name} · {v.fit === 'HIGH' ? '最像' : v.fit === 'MEDIUM' ? '可投' : '不建议'}
                    </span>
                  );
                })}
              </div>
            </div>
          )}

          {verdict.vocabularyGaps && verdict.vocabularyGaps.length > 0 && (
            <div className="mb-3">
              <div className="mb-1.5 text-xs font-semibold text-slate-500">搜索词汇覆盖（做了但没用行业术语，检索命中损失）</div>
              {verdict.vocabularyGaps.map((v, i) => (
                <div key={i} className="rounded-lg bg-white/80 px-3 py-1.5 text-xs text-slate-600">
                  <span className="font-medium text-slate-700">「{v.usedSynonym}」</span>→ 建议补充术语
                  <span className="font-semibold text-sky-700">「{v.term}」</span>
                </div>
              ))}
            </div>
          )}

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
          {verdict.experienceStrength && verdict.experienceStrength.length > 0 && (
            <div className="space-y-1.5">
              {verdict.experienceStrength.map((e, i) => (
                <div key={i} className="rounded-lg bg-white/80 px-3 py-2 text-xs">
                  <div className="flex items-center gap-2">
                    <span className="line-clamp-1 font-medium text-slate-700">{e.entryRef}</span>
                    {e.attribution && (
                      <span className={`ml-auto shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                        e.attribution === 'LEAD' || e.attribution === 'OWNER' ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500'}`}>
                        {e.attribution}
                      </span>
                    )}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-1 text-[11px]">
                    {['situation', 'task', 'action', 'result'].map((k) => (
                      <span key={k} className={`rounded px-1.5 py-0.5 ${e.star?.[k] ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-400'}`}>
                        {k.toUpperCase()}
                      </span>
                    ))}
                    {e.resultQuality && (
                      <span className="ml-auto text-slate-400">结果：{e.resultQuality}</span>
                    )}
                  </div>
                  {e.concern && <div className="mt-1 text-[11px] text-rose-500">⚠ {e.concern}</div>}
                </div>
              ))}
            </div>
          )}
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

      {/* 面试杠杆：简历是面试的剧本，默认折叠 */}
      {verdict.leverageCards && verdict.leverageCards.length > 0 && (
        <details className="group">
          <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-semibold text-amber-600">
            <HelpCircle size={15} />
            面试预演（{verdict.leverageCards.length} 项：面试官会问什么、怎么接）
          </summary>
          <div className="mt-2 grid gap-2 md:grid-cols-2">
            {verdict.leverageCards.map((card: LeverageCardType, i) => (
              <div key={i} className={`rounded-xl border p-3 text-xs ${card.kind === 'STRENGTH' ? 'border-emerald-200 bg-emerald-50/60' : 'border-rose-200 bg-rose-50/60'}`}>
                <div className="flex items-center gap-1.5 font-semibold text-slate-700">
                  {card.kind === 'STRENGTH' ? <CheckCircle size={13} className="text-emerald-500" /> : <AlertTriangle size={13} className="text-rose-500" />}
                  <span className="line-clamp-1">{card.point}</span>
                </div>
                {card.likelyQuestion && (
                  <div className="mt-2 rounded-lg bg-white/80 px-2.5 py-1.5 text-slate-600">
                    <span className="font-medium text-slate-700">Q：</span>{card.likelyQuestion}
                  </div>
                )}
                {card.kind === 'STRENGTH' && card.prepHint && (
                  <div className="mt-1 text-slate-500">准备：{card.prepHint}</div>
                )}
                {card.kind === 'RISK' && card.defenseStrategy && (
                  <div className="mt-1 text-slate-500">应答：{card.defenseStrategy}</div>
                )}
              </div>
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
  const hasQualityScore = result.qualityScore != null;
  const hasEnhancedKeyPoints = result.enhancedKeyPoints && result.enhancedKeyPoints.length > 0;
  const hasEnhancedRisks = result.enhancedRisks && result.enhancedRisks.length > 0;
  const hasDeepAnalysis = hasProfile || hasQualityScore || hasEnhancedKeyPoints || hasEnhancedRisks;

  return (
    <div className="space-y-5 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-base font-bold text-slate-800">简历分析报告</h3>
        <ModeBadge mode={mode} />
      </div>

      {/* 候选人画像 */}
      {hasProfile && <ProfileBar profile={result.profile!} />}

      {result.projectFacts && result.projectFacts.length > 0 && (
        <ProjectFactsSection projects={result.projectFacts} onCitation={onCitation} />
      )}

      {/* P12 漏斗式结论（新主结果，按"会死在哪一关"排序） */}
      {result.funnelVerdict && <FunnelVerdictSection verdict={result.funnelVerdict} onCitation={onCitation} />}

      {result.diagnoses && <DiagnosisSection diagnoses={result.diagnoses} onCitation={onCitation} />}

      {/* 质量评分（P11 历史 run 兼容） */}
      {hasQualityScore && <QualityScoreCard score={result.qualityScore!} />}

      {/* 候选人画像（LLM 总结；判断在结论区顶部的一句话裁决，不再混在一起） */}
      {result.summary && (
        <div className="rounded-xl bg-gradient-to-br from-indigo-50 to-violet-50 p-4">
          <div className="mb-1 text-xs font-semibold text-indigo-400">候选人画像</div>
          <div className="text-sm leading-7 text-slate-800">{result.summary}</div>
        </div>
      )}

      {/* 增强亮点 */}
      {hasEnhancedKeyPoints && (
        <EnhancedKeyPointsSection keyPoints={result.enhancedKeyPoints!} onCitation={onCitation} />
      )}

      {/* 增强风险 */}
      {hasEnhancedRisks && (
        <EnhancedRisksSection risks={result.enhancedRisks!} onCitation={onCitation} />
      )}

      {/* JD 匹配维度 */}
      {result.matchDimensions && result.matchDimensions.length > 0 && (
        <div>
          <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-sky-600">
            <CheckCircle size={15} />
            JD 匹配维度
          </div>
          <div className="space-y-2">
            {result.matchDimensions.map((dim, i) => {
              const levelColor = dim.level === '高' ? 'bg-emerald-100 text-emerald-700'
                : dim.level === '中' ? 'bg-amber-100 text-amber-700'
                : 'bg-rose-100 text-rose-700';
              return (
                <div key={i} className="rounded-lg bg-sky-50/60 p-3">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-slate-700">{dim.name}</span>
                    <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${levelColor}`}>{dim.level}</span>
                  </div>
                  <p className="mt-1 text-xs leading-5 text-slate-500">{dim.reason}</p>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* 降级到通用展示：如果没有深度分析数据，回退到 keyPoints/risks */}
      {!hasDeepAnalysis && (
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
