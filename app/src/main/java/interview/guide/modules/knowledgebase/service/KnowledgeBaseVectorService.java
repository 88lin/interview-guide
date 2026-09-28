package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.transaction.TransactionalExecutor;
import jakarta.annotation.PostConstruct;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private static final String METADATA_CHUNK_STRATEGY = "chunk_strategy";
    private final VectorStore vectorStore;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final KnowledgeBaseVectorProperties vectorProperties;
    private final KnowledgeBasePersistenceService persistenceService;

    @Autowired
    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor,
        KnowledgeBaseVectorProperties vectorProperties,
        KnowledgeBasePersistenceService persistenceService
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        this.vectorProperties = vectorProperties;
        this.persistenceService = persistenceService;
        // Chunk 策略由 app.ai.rag.vectorization 配置驱动（仅 Spring AI 原生参数，无 overlap）
        this.textSplitter = vectorProperties.createTextSplitter();
    }

    KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this(vectorStore, vectorRepository, null, new KnowledgeBaseVectorProperties(), null);
    }

    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        vectorizeAndStore(knowledgeBaseId, content, () -> { });
    }

    /**
     * 带进度心跳回调的向量化：分块完成与每个 Embedding 批次后回调（由调用方决定节流）。
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content, Runnable progressHeartbeat) {
        vectorizeAndStore(knowledgeBaseId, content, null, progressHeartbeat);
    }

    /**
     * 带执行代次的向量化。临时向量仅在数据库锁内确认当前代次仍有效后才会被提升。
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content, String attemptId,
                                  Runnable progressHeartbeat) {
        vectorizeAndStore(knowledgeBaseId, content, attemptId, progressHeartbeat,
            new SourceInfo("", "", null));
    }

    /** 文件哈希只用于可复现标识，不用于鉴权；来源信息由已加载的业务实体提供。 */
    public record SourceInfo(String name, String filename, String fileHash) {}

    public void vectorizeAndStore(Long knowledgeBaseId, String content, String attemptId,
                                  Runnable progressHeartbeat, SourceInfo source) {
        String jobId = null;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}",
                knowledgeBaseId, jobId, content.length());

            // 1. 将文本分块
            String configJson = vectorConfigJson();
            String fileHash = source.fileHash() == null || source.fileHash().isBlank()
                ? DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8)) : source.fileHash();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_kb_id", knowledgeBaseId.toString());
            metadata.put("source_name", source.name() == null ? "" : source.name());
            metadata.put("source_filename", source.filename() == null ? "" : source.filename());
            metadata.put("chunk_strategy", vectorProperties.getStrategyVersion());
            List<Document> chunks = textSplitter.apply(List.of(new Document(content, metadata)));
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("chunk_index", i);
                chunk.getMetadata().put("total_chunks", chunks.size());
                // 仅 metadata 稳定；保留随机 Document ID，避免 pending upsert 覆盖旧正式向量。
                chunk.getMetadata().put("source_id", DigestUtils.md5DigestAsHex(
                    (fileHash + "\n" + configJson + "\n" + i).getBytes(StandardCharsets.UTF_8)));
            }
            
            log.info("文本分块完成: {} 个chunks", chunks.size());
            
            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, jobId);

            // 分块完成心跳
            progressHeartbeat.run();

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
                // Embedding 批次心跳（调用方节流）
                progressHeartbeat.run();
            }
            if (attemptId != null && persistenceService != null) {
                persistenceService.activateVectorJobAndUpdateSnapshot(
                    knowledgeBaseId, attemptId, jobId, totalChunks, configJson);
            } else {
                activateVectorJob(knowledgeBaseId, jobId);
                // 兼容直接调用：提升成功后再以独立短事务写入快照
                updateVectorizationSnapshot(knowledgeBaseId, totalChunks, configJson);
            }
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败");
        }
    }

    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
            // 在 add 前写入策略标识：promote 只改 kb_id，不会回填
            chunk.getMetadata().put(METADATA_CHUNK_STRATEGY, vectorProperties.getStrategyVersion());
        });
    }
    
    /**
     * 基于多个知识库进行相似度搜索
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: queryLength={}, kbIds={}, topK={}, minScore={}",
            query.length(), knowledgeBaseIds, topK, minScore);
        
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            // Apply topK limiting in case VectorStore returns more than requested
            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            return limitedResults;
            
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败");
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     * 
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId));
    }

    private void activateVectorJob(Long knowledgeBaseId, String jobId) {
        runVectorRepositoryMutation(() -> {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        });
    }

    /**
     * 向量化成功后的配置快照：chunkCount、vectorConfig（无密钥 JSON）、vectorizedAt。
     * 失败（含清理）路径不会调用；与 Embedding 写入不在同一事务。
     */
    private void updateVectorizationSnapshot(Long knowledgeBaseId, int chunkCount,
                                             String configJson) {
        if (persistenceService == null) {
            return;
        }
        Runnable update = () -> persistenceService.updateVectorizationSnapshot(
            knowledgeBaseId, chunkCount, configJson);
        if (transactionalExecutor == null) {
            update.run();
            return;
        }
        transactionalExecutor.run(update);
    }

    private String vectorConfigJson() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("splitter", vectorProperties.getSplitter());
        config.put("chunkSize", vectorProperties.getChunkSize());
        config.put("minChunkSizeChars", vectorProperties.getMinChunkSizeChars());
        config.put("minChunkLengthToEmbed", vectorProperties.getMinChunkLengthToEmbed());
        config.put("maxNumChunks", vectorProperties.getMaxNumChunks());
        config.put("keepSeparator", vectorProperties.isKeepSeparator());
        config.put("punctuationMarks", vectorProperties.getPunctuationMarks());
        config.put("strategyVersion", vectorProperties.getStrategyVersion());
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(config);
        } catch (tools.jackson.core.JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "向量化配置快照序列化失败");
        }
    }

    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(cleanupError),
                ErrorLogSanitizer.forLogging(cleanupError));
        }
    }

    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }
}
