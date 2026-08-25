package com.gcll.docagent.analysis;

import com.gcll.docagent.parsing.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 已解析文档的内存缓存：runId → ParsedDocument。
 * 供工具层（read_section / search_document）按 runId 取文档；容量上限防止内存膨胀。
 */
@Component
public class DocumentStore {

    private static final int MAX_ENTRIES = 64;

    private final Map<String, ParsedDocument> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ParsedDocument> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized void put(String runId, ParsedDocument document) {
        cache.put(runId, document);
    }

    public synchronized Optional<ParsedDocument> get(String runId) {
        return Optional.ofNullable(cache.get(runId));
    }
}
