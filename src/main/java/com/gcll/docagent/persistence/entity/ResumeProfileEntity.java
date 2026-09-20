package com.gcll.docagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 简历档案——按内容哈希去重的已解析简历。
 * 相同内容的简历（重复上传或不同格式导出）只存一份；再次分析可直接取库，免重复上传。
 */
@TableName("resume_profile")
public class ResumeProfileEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    /** 解析后全文（ParsedDocument.fullText()）的 SHA-256，档案去重键 */
    private String contentHash;
    private String fileName;
    private String fileType;
    private Integer charCount;
    /** ParsedDocument 完整 JSON（分节全文），反序列化后可直接交给分析流水线 */
    private String parsedJson;
    /** 使用该简历发起的分析次数 */
    private Integer runCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public Integer getCharCount() { return charCount; }
    public void setCharCount(Integer charCount) { this.charCount = charCount; }

    public String getParsedJson() { return parsedJson; }
    public void setParsedJson(String parsedJson) { this.parsedJson = parsedJson; }

    public Integer getRunCount() { return runCount; }
    public void setRunCount(Integer runCount) { this.runCount = runCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
