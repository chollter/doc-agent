package com.gcll.docagent.analysis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.persistence.entity.AnalysisCacheEntity;
import com.gcll.docagent.persistence.entity.ResumeProfileEntity;
import com.gcll.docagent.persistence.mapper.AnalysisCacheMapper;
import com.gcll.docagent.persistence.mapper.ResumeProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 简历档案 + 分析结论缓存。
 * <p>档案（resume_profile）按解析后全文的 SHA-256 去重，独立于缓存开关——免上传再分析依赖它。
 * <p>结论缓存（analysis_cache）键 = 内容哈希 + 分析输入（技能/指令/JD/方向/人群/prompt 版本），
 * 任一输入变化自然失效；命中时新 run 直接复制历史结论完成，跳过整条 LLM 流水线。
 * <p>所有写路径吞异常：缓存失败只降级为"重新分析"，不影响主流程。
 */
@Service
public class ResumeCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResumeCacheService.class);
    /** cache_key 各输入分段的分隔符——控制字符不会出现在用户输入里 */
    private static final char SEP = '\u0001';

    private final ResumeProfileMapper profileMapper;
    private final AnalysisCacheMapper cacheMapper;
    private final ObjectMapper objectMapper;
    private final boolean cacheEnabled;

    public ResumeCacheService(ResumeProfileMapper profileMapper,
                              AnalysisCacheMapper cacheMapper,
                              ObjectMapper objectMapper,
                              @Value("${docagent.analysis.result-cache-enabled:true}") boolean cacheEnabled) {
        this.profileMapper = profileMapper;
        this.cacheMapper = cacheMapper;
        this.objectMapper = objectMapper;
        this.cacheEnabled = cacheEnabled;
    }

    /** 结论缓存是否启用（档案记录不受此开关影响）。 */
    public boolean cacheEnabled() {
        return cacheEnabled;
    }

    /** 简历内容哈希：解析后全文的 SHA-256——"简历相同"的唯一判定依据。 */
    public static String contentHash(ParsedDocument doc) {
        return sha256Hex(doc.fullText());
    }

    /** 缓存键：内容哈希 + 分析输入的组合哈希。入参取 run 上已规范化的值。 */
    public static String buildCacheKey(String contentHash, String skill, String instruction,
                                       String jobDescription, String targetDirection,
                                       String persona, String promptVersion) {
        String joined = String.join(String.valueOf(SEP),
                nullToEmpty(contentHash), nullToEmpty(skill), nullToEmpty(instruction),
                nullToEmpty(jobDescription), nullToEmpty(targetDirection),
                nullToEmpty(persona), nullToEmpty(promptVersion));
        return sha256Hex(joined);
    }

    /** 档案去重写入：同内容已存在则刷新文件元数据/使用计数，返回档案引用。 */
    public ProfileRef upsertProfile(ParsedDocument doc) {
        String hash = contentHash(doc);
        try {
            ResumeProfileEntity existing = findByHash(hash);
            if (existing == null) {
                ResumeProfileEntity entity = new ResumeProfileEntity();
                entity.setId("rsm-" + UUID.randomUUID());
                entity.setContentHash(hash);
                entity.setFileName(doc.fileName());
                entity.setFileType(doc.fileType());
                entity.setCharCount(doc.totalChars());
                entity.setParsedJson(objectMapper.writeValueAsString(doc));
                entity.setRunCount(1);
                entity.setCreatedAt(LocalDateTime.now());
                entity.setLastUsedAt(LocalDateTime.now());
                try {
                    profileMapper.insert(entity);
                } catch (DuplicateKeyException race) {
                    // 并发同内容提交：另一条已建档，转为计数刷新
                    existing = findByHash(hash);
                }
                return new ProfileRef(entity.getId(), hash);
            }
            existing.setFileName(doc.fileName());
            existing.setFileType(doc.fileType());
            existing.setRunCount((existing.getRunCount() == null ? 0 : existing.getRunCount()) + 1);
            existing.setLastUsedAt(LocalDateTime.now());
            profileMapper.updateById(existing);
            return new ProfileRef(existing.getId(), hash);
        } catch (DuplicateKeyException ignored) {
            ResumeProfileEntity winner = findByHash(hash);
            return new ProfileRef(winner != null ? winner.getId() : null, hash);
        } catch (Exception ex) {
            log.warn("简历档案写入失败（不影响分析，仅失去免上传复用）: {}", ex.getMessage());
            return new ProfileRef(null, hash);
        }
    }

    /** 按 resumeId 取已存简历（免上传再分析的数据源）。 */
    public Optional<ParsedDocument> loadDocument(String resumeId) {
        ResumeProfileEntity entity = profileMapper.selectById(resumeId);
        if (entity == null) {
            return Optional.empty();
        }
        return parse(entity.getParsedJson());
    }

    /** 按内容哈希取已存简历——run 文档视图的最终兜底。 */
    public Optional<ParsedDocument> findDocumentByHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return Optional.empty();
        }
        ResumeProfileEntity entity = findByHash(hash);
        if (entity == null) {
            return Optional.empty();
        }
        return parse(entity.getParsedJson());
    }

    /** 档案列表（最近使用在前）。 */
    public List<ResumeProfileEntity> listProfiles() {
        return profileMapper.selectList(new LambdaQueryWrapper<ResumeProfileEntity>()
                .orderByDesc(ResumeProfileEntity::getLastUsedAt)
                .last("LIMIT 50"));
    }

    /** 缓存命中查询：返回历史结论。 */
    public Optional<CachedResult> findCached(String cacheKey) {
        if (!cacheEnabled || cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }
        try {
            AnalysisCacheEntity entity = cacheMapper.selectOne(
                    new LambdaQueryWrapper<AnalysisCacheEntity>().eq(AnalysisCacheEntity::getCacheKey, cacheKey));
            if (entity == null) {
                return Optional.empty();
            }
            return Optional.of(new CachedResult(
                    entity.getResultJson(), entity.getScoreOverall(),
                    entity.getScoreDimensions(), entity.getSourceRunId()));
        } catch (Exception ex) {
            log.warn("分析缓存读取失败（按未命中处理）: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** 分析完成后写入/覆盖结论缓存（按 cache_key upsert；失败只记日志）。 */
    public void storeResult(AgentRun run, ParsedDocument doc) {
        if (!cacheEnabled || run.getResultJson() == null) {
            return;
        }
        try {
            ProfileRef profile = upsertProfile(doc);
            if (profile.id() == null) {
                return;
            }
            String hash = contentHash(doc);
            String cacheKey = buildCacheKey(hash, run.getSkill(), run.getInstruction(),
                    run.getJobDescription(), run.getTargetDirection(), run.getPersona(), run.getPromptVersion());
            AnalysisCacheEntity entity = cacheMapper.selectOne(
                    new LambdaQueryWrapper<AnalysisCacheEntity>().eq(AnalysisCacheEntity::getCacheKey, cacheKey));
            boolean exists = entity != null;
            if (!exists) {
                entity = new AnalysisCacheEntity();
                entity.setId("cac-" + UUID.randomUUID());
                entity.setCacheKey(cacheKey);
                entity.setCreatedAt(LocalDateTime.now());
            }
            entity.setResumeId(profile.id());
            entity.setSkill(run.getSkill());
            entity.setInstruction(run.getInstruction());
            entity.setJobDescription(run.getJobDescription());
            entity.setTargetDirection(run.getTargetDirection());
            entity.setPersona(run.getPersona());
            entity.setPromptVersion(run.getPromptVersion());
            entity.setResultJson(run.getResultJson());
            entity.setScoreOverall(run.getScoreOverall());
            entity.setScoreDimensions(run.getScoreDimensions());
            entity.setSourceRunId(run.getId());
            entity.setUpdatedAt(LocalDateTime.now());
            if (exists) {
                cacheMapper.updateById(entity);
            } else {
                try {
                    cacheMapper.insert(entity);
                } catch (DuplicateKeyException race) {
                    // 并发完成同 key：留任一条即可，缓存语义不受影响
                    log.debug("分析缓存并发写入冲突，保留已有记录: runId={}", run.getId());
                }
            }
        } catch (Exception ex) {
            log.warn("分析缓存写入失败（不影响主流程）: {}", ex.getMessage());
        }
    }

    private ResumeProfileEntity findByHash(String hash) {
        return profileMapper.selectOne(
                new LambdaQueryWrapper<ResumeProfileEntity>().eq(ResumeProfileEntity::getContentHash, hash));
    }

    private Optional<ParsedDocument> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ParsedDocument.class));
        } catch (Exception ex) {
            log.warn("简历档案反序列化失败: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** 档案引用：id 为 null 表示建档失败（哈希仍可用于 run 标记）。 */
    public record ProfileRef(String id, String contentHash) {
    }

    /** 命中的历史结论。 */
    public record CachedResult(String resultJson, Integer scoreOverall,
                               String scoreDimensions, String sourceRunId) {
    }
}
