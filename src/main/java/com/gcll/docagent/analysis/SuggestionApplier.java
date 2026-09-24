package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.api.BusinessException;
import com.gcll.docagent.api.ErrorCode;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 建议采纳引擎（无状态、幂等）：对指定建议做 before→after 替换，产出修改稿（Markdown）。
 * <p>匹配用空白弹性正则（PDF 抽取的换行/多空格不丢锚点）；before 在全文任何节都
 * 定位不到时进 missingBefores——不静默失败。PDF 原件不可编辑，修改稿是文本形态，
 * 用户下载后回填自己的源文件，这是简历修改工具的标准形态。
 */
@Component
public class SuggestionApplier {

    private final AgentRunRepository agentRunRepository;
    private final DocumentStore documentStore;
    private final ObjectMapper objectMapper;

    public SuggestionApplier(AgentRunRepository agentRunRepository,
                             DocumentStore documentStore,
                             ObjectMapper objectMapper) {
        this.agentRunRepository = agentRunRepository;
        this.documentStore = documentStore;
        this.objectMapper = objectMapper;
    }

    public AppliedRevision applySuggestions(String runId, List<Integer> indices) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "run 不存在: " + runId));
        if (run.getStatus() != AgentRunStatus.COMPLETED || run.getResultJson() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "run 未完成，无法采纳建议");
        }
        ParsedDocument doc = documentStore.get(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE, "文档快照已过期（服务重启），请重新分析"));
        try {
            AnalysisResult result = objectMapper.readValue(run.getResultJson(), AnalysisResult.class);
            List<ActionableSuggestion> suggestions = result.actionableSuggestions() == null
                    ? List.of() : result.actionableSuggestions();
            List<String> missing = new ArrayList<>();
            List<String> changedSectionIds = new ArrayList<>();
            int applied = 0;
            // section 文本可变副本：逐条建议依次替换（多条建议作用于同一文档时累积生效）
            List<String> texts = new ArrayList<>(doc.sections().stream().map(DocSection::text).toList());
            for (Integer idx : indices == null ? List.<Integer>of() : indices) {
                if (idx == null || idx < 0 || idx >= suggestions.size()) {
                    continue;
                }
                ActionableSuggestion sug = suggestions.get(idx);
                if (sug.before() == null || sug.after() == null) {
                    continue;
                }
                boolean replaced = false;
                for (int i = 0; i < texts.size() && !replaced; i++) {
                    String next = flexibleReplace(texts.get(i), sug.before(), sug.after());
                    if (next != null) {
                        texts.set(i, next);
                        replaced = true;
                        String sectionId = doc.sections().get(i).id();
                        if (!changedSectionIds.contains(sectionId)) {
                            changedSectionIds.add(sectionId);
                        }
                    }
                }
                if (replaced) {
                    applied++;
                } else {
                    missing.add("#" + idx + " " + truncateFor(sug.before()));
                }
            }
            StringBuilder md = new StringBuilder();
            for (int i = 0; i < doc.sections().size(); i++) {
                DocSection sec = doc.sections().get(i);
                if (sec.heading() != null && !sec.heading().isBlank()) {
                    md.append("## ").append(sec.heading()).append("\n\n");
                }
                md.append(texts.get(i)).append("\n\n");
            }
            return new AppliedRevision(md.toString().trim(), applied,
                    indices == null ? 0 : indices.size(), missing, changedSectionIds);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "采纳失败: " + ex.getMessage());
        }
    }

    /** 空白弹性替换：把 before 按空白切段、逐段 Pattern.quote、以 \s+ 连接成模式，命中则替换为 after。 */
    private static String flexibleReplace(String text, String before, String after) {
        String[] tokens = before.strip().split("\s+");
        if (tokens.length == 0) {
            return null;
        }
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                pattern.append("\s+");
            }
            pattern.append(Pattern.quote(tokens[i]));
        }
        try {
            Matcher m = Pattern.compile(pattern.toString()).matcher(text);
            if (m.find()) {
                return new StringBuilder(text).replace(m.start(), m.end(), after).toString();
            }
        } catch (Exception ignored) {
            // 模式异常视为未命中
        }
        return null;
    }

    private static String truncateFor(String s) {
        String t = s.replaceAll("\s+", " ").trim();
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }
}
