package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.common.async.recovery.VectorizeRecoveryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 知识库向量化 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行向量化
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseParseService parseService;
    private final VectorizeRecoveryProperties recoveryProperties;
    /** 当前任务的 Embedding 心跳上次写库时间（领取时清零），仅内存态 */
    private volatile long lastEmbeddingHeartbeatNanos;

    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeBaseParseService parseService,
        VectorizeRecoveryProperties recoveryProperties
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.parseService = parseService;
        this.recoveryProperties = recoveryProperties;
    }

    static final class VectorizePayload {

        private final Long kbId;
        private String attemptId;

        VectorizePayload(Long kbId) {
            this.kbId = kbId;
        }

        Long kbId() {
            return kbId;
        }

        String attemptId() {
            return attemptId;
        }

        void setAttemptId(String attemptId) {
            this.attemptId = attemptId;
        }
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        if (kbIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        // 兼容旧格式消息：其中的 content 字段直接忽略，正文以数据库实体 + RustFS 为事实来源
        return new VectorizePayload(Long.parseLong(kbIdStr));
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected boolean shouldSkip(VectorizePayload payload) {
        return knowledgeBaseRepository.findById(payload.kbId())
            .map(kb -> kb.getVectorStatus() == VectorStatus.COMPLETED)
            .orElse(true);
    }

    /**
     * 条件领取模式下 markProcessing 不再使用（照抄题目生成消费者的做法）。
     */
    @Override
    protected void markProcessing(VectorizePayload payload) {
        // tryMarkProcessing 原子领取
    }

    /**
     * 条件领取：只有 PENDING → PROCESSING 成功的消费者才执行任务（多实例防重复）。
     */
    @Override
    protected boolean tryMarkProcessing(VectorizePayload payload) {
        String attemptId = UUID.randomUUID().toString();
        boolean claimed = knowledgeBaseRepository.tryMarkVectorProcessing(
            payload.kbId(), attemptId, java.time.LocalDateTime.now()) == 1;
        if (claimed) {
            payload.setAttemptId(attemptId);
            lastEmbeddingHeartbeatNanos = 0;
        }
        return claimed;
    }

    /**
     * 心跳：独立短事务条件更新，只推进仍为 PROCESSING 的行。
     */
    private void heartbeat(VectorizePayload payload) {
        int updated = knowledgeBaseRepository.heartbeatVectorProcessing(
            payload.kbId(), payload.attemptId(), java.time.LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "向量化任务执行权已失效");
        }
    }

    /**
     * Embedding 阶段节流心跳：距上次超过阈值才写库一次。
     */
    void throttledEmbeddingHeartbeat(VectorizePayload payload) {
        long now = System.nanoTime();
        long throttleNanos = recoveryProperties.getHeartbeatThrottle().toNanos();
        if (lastEmbeddingHeartbeatNanos != 0 && now - lastEmbeddingHeartbeatNanos < throttleNanos) {
            return;
        }
        heartbeat(payload);
        lastEmbeddingHeartbeatNanos = now;
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        Long kbId = payload.kbId();
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) {
            log.warn("知识库已被删除，跳过向量化任务: kbId={}", kbId);
            return;
        }
        if (isBlank(kb.getStorageKey()) || isBlank(kb.getOriginalFilename())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "知识库缺少存储信息，无法下载原文件");
        }
        String content = parseService.downloadAndParseContent(kb.getStorageKey(), kb.getOriginalFilename());
        // 下载 + 解析完成心跳
        heartbeat(payload);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }
        vectorService.vectorizeAndStore(kbId, content, payload.attemptId(), () -> {
            // 分块完成与每个 Embedding 批次后的节流心跳
            throttledEmbeddingHeartbeat(payload);
        }, new KnowledgeBaseVectorService.SourceInfo(kb.getName(), kb.getOriginalFilename(), kb.getFileHash()));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        // 条件写入：状态已不是 PROCESSING（被恢复/重试路径改写）时 no-op，防止覆盖他人结果
        knowledgeBaseRepository.completeVectorIfProcessing(
            payload.kbId(), payload.attemptId(), java.time.LocalDateTime.now());
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        knowledgeBaseRepository.failVectorIfProcessing(
            payload.kbId(), payload.attemptId(), truncateError(error),
            java.time.LocalDateTime.now());
    }

    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        // 条件领取的配套重置：PROCESSING → PENDING 成功才重新投递，否则任务已完成/被替代，只记录并 ACK 丢弃
        int reset = knowledgeBaseRepository.resetVectorToPending(
            kbId, payload.attemptId(), java.time.LocalDateTime.now());
        if (reset != 1) {
            log.warn("重试重置失败（任务已完成或状态不再匹配），ACK 丢弃: kbId={}, retryCount={}", kbId, retryCount);
            return;
        }
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: kbId={}, error={}", kbId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            knowledgeBaseRepository.failVectorIfPending(
                kbId, "重试入队失败", java.time.LocalDateTime.now());
        }
    }


}
