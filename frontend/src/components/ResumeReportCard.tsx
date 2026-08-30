import { AlertTriangle, Award, Bookmark, Briefcase, CheckCircle, ClipboardList, GraduationCap, HelpCircle, Lightbulb, Target, TrendingUp, User, XCircle } from 'lucide-react';
import type { AnalysisResult, ActionableSuggestion, EnhancedKeyPoint, EnhancedRisk, ResumeProfile, QualityScore, SkillMatrix } from '../api/analysis';

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

/** 精准建议区 */
function ActionableSuggestionsSection({ suggestions, onCitation }: {
  suggestions: ActionableSuggestion[];
  onCitation?: (sectionId: string) => void;
}) {
  if (!suggestions || suggestions.length === 0) return null;

  const severityConfig = {
    HIGH: { bg: 'bg-rose-50', border: 'border-rose-200', badge: 'bg-rose-100 text-rose-700', label: '高优' },
    MEDIUM: { bg: 'bg-amber-50', border: 'border-amber-200', badge: 'bg-amber-100 text-amber-700', label: '中优' },
    LOW: { bg: 'bg-sky-50', border: 'border-sky-200', badge: 'bg-sky-100 text-sky-700', label: '低优' },
  };

  return (
    <div>
      <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-emerald-600">
        <Target size={15} />
        精准改进建议
      </div>
      <div className="space-y-3">
        {suggestions.map((s, i) => {
          const cfg = severityConfig[s.severity] ?? severityConfig.LOW;
          return (
            <div key={i} className={`rounded-xl border ${cfg.border} ${cfg.bg} p-4`}>
              <div className="mb-2 flex items-center gap-2">
                <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${cfg.badge}`}>{cfg.label}</span>
                <span className="text-sm font-medium text-slate-700">{s.target}</span>
                {s.sectionId && (
                  <button
                    type="button"
                    onClick={() => onCitation?.(s.sectionId!)}
                    className="ml-auto text-xs text-indigo-500 hover:underline"
                  >
                    定位 →
                  </button>
                )}
              </div>
              <div className="space-y-2">
                <div className="rounded-lg bg-white/80 p-2.5">
                  <div className="mb-1 text-xs font-semibold text-rose-500">原文</div>
                  <p className="text-sm leading-6 text-slate-600 line-through decoration-rose-300">{s.before}</p>
                </div>
                <div className="rounded-lg bg-white/80 p-2.5">
                  <div className="mb-1 text-xs font-semibold text-emerald-500">改写建议</div>
                  <p className="text-sm leading-6 text-slate-700">{s.after}</p>
                </div>
              </div>
              <p className="mt-2 text-xs leading-5 text-slate-500">💡 {s.reason}</p>
            </div>
          );
        })}
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

/** 简历专属分析报告 */
export default function ResumeReportCard({ result, mode, onCitation }: {
  result: AnalysisResult;
  mode: string | null | undefined;
  onCitation?: (sectionId: string) => void;
}) {
  const hasProfile = result.profile != null;
  const hasQualityScore = result.qualityScore != null;
  const hasActionableSuggestions = result.actionableSuggestions && result.actionableSuggestions.length > 0;
  const hasEnhancedKeyPoints = result.enhancedKeyPoints && result.enhancedKeyPoints.length > 0;
  const hasEnhancedRisks = result.enhancedRisks && result.enhancedRisks.length > 0;
  const hasDeepAnalysis = hasProfile || hasQualityScore || hasActionableSuggestions || hasEnhancedKeyPoints || hasEnhancedRisks;

  return (
    <div className="space-y-5 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-base font-bold text-slate-800">简历分析报告</h3>
        <ModeBadge mode={mode} />
      </div>

      {/* 候选人画像 */}
      {hasProfile && <ProfileBar profile={result.profile!} />}

      {/* 质量评分 */}
      {hasQualityScore && <QualityScoreCard score={result.qualityScore!} />}

      {/* 总结 */}
      {!hasDeepAnalysis && (
        <div className="rounded-xl bg-gradient-to-br from-indigo-50 to-violet-50 p-4 text-sm leading-7 text-slate-800">
          {result.summary}
        </div>
      )}

      {/* 有深度分析时，summary 作为补充 */}
      {hasDeepAnalysis && (
        <div className="rounded-xl bg-gradient-to-br from-indigo-50 to-violet-50 p-4 text-sm leading-7 text-slate-800">
          {result.summary}
        </div>
      )}

      {/* 精准建议 */}
      {hasActionableSuggestions && (
        <ActionableSuggestionsSection suggestions={result.actionableSuggestions!} onCitation={onCitation} />
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

      {/* 差距分析 */}
      {result.gaps && result.gaps.length > 0 && (
        <div>
          <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-rose-600">
            <XCircle size={15} />
            差距分析
          </div>
          <div className="space-y-2">
            {result.gaps.map((gap, i) => (
              <div key={i} className="rounded-lg bg-rose-50/60 p-3">
                <div className="text-xs font-semibold text-slate-600">JD 要求</div>
                <p className="text-sm text-slate-700">{gap.requirement}</p>
                <div className="mt-1.5 text-xs font-semibold text-rose-500">简历差距</div>
                <p className="text-sm text-slate-600">{gap.gap}</p>
                <div className="mt-1.5 text-xs font-semibold text-emerald-500">改进建议</div>
                <p className="text-sm text-slate-600">{gap.suggestion}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 面试题预测 */}
      {result.interviewQuestions && result.interviewQuestions.length > 0 && (
        <div>
          <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-violet-600">
            <HelpCircle size={15} />
            面试题预测
          </div>
          <div className="space-y-2">
            {result.interviewQuestions.map((q, i) => (
              <div key={i} className="rounded-lg bg-violet-50/60 p-3">
                <div className="flex items-start gap-2">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-violet-200 text-xs font-bold text-violet-700">{i + 1}</span>
                  <div>
                    <p className="text-sm font-medium text-slate-700">{q.question}</p>
                    <p className="mt-1 text-xs text-slate-500"><span className="font-semibold">考察点：</span>{q.intent}</p>
                    <p className="mt-1 text-xs text-slate-500"><span className="font-semibold">回答建议：</span>{q.suggestedAnswer}</p>
                    {q.isGapPrep && (
                      <span className="mt-1 inline-block rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-700">差距准备</span>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 降级到通用展示：如果没有深度分析数据，回退到 keyPoints/risks/suggestions */}
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
          {result.suggestions?.length > 0 && (
            <div>
              <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-emerald-600">
                <Lightbulb size={15} />
                建议
              </div>
              <ul className="space-y-1.5">
                {result.suggestions.map((item, i) => (
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
