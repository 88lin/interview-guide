package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.common.async.recovery.VectorizeRecoveryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("向量化消费者 ID-only 消息测试")
class VectorizeStreamConsumerTest {

  @Mock
  private RedisService redisService;
  @Mock
  private KnowledgeBaseVectorService vectorService;
  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private KnowledgeBaseParseService parseService;

  private VectorizeStreamConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new VectorizeStreamConsumer(redisService, vectorService,
        knowledgeBaseRepository, parseService, new VectorizeRecoveryProperties());
  }

  private KnowledgeBaseEntity kb(String storageKey, String filename) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(7L);
    kb.setStorageKey(storageKey);
    kb.setOriginalFilename(filename);
    return kb;
  }

  private VectorizeStreamConsumer.VectorizePayload payload() {
    VectorizeStreamConsumer.VectorizePayload payload =
        new VectorizeStreamConsumer.VectorizePayload(7L);
    payload.setAttemptId("attempt-1");
    return payload;
  }

  @Test
  @DisplayName("解析旧格式消息时忽略 content 字段")
  void shouldIgnoreLegacyContentField() {
    VectorizeStreamConsumer.VectorizePayload payload = consumer.parsePayload(
        new StreamMessageId(0, 0),
        Map.of("kbId", "7", "content", "旧格式正文", "retryCount", "0"));

    assertThat(payload.kbId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("缺少 kbId 的消息被丢弃")
  void shouldRejectMessageWithoutKbId() {
    VectorizeStreamConsumer.VectorizePayload payload = consumer.parsePayload(
        new StreamMessageId(0, 0),
        Map.of("retryCount", "0"));

    assertThat(payload).isNull();
  }

  @Nested
  @DisplayName("processBusiness 业务处理")
  class ProcessBusiness {

    @Test
    @DisplayName("实体已删除时 ACK 丢弃且不向量化")
    void shouldSkipWhenEntityDeleted() {
      when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.empty());

      consumer.processBusiness(payload());

      verify(vectorService, never()).vectorizeAndStore(anyLong(), anyString());
    }

    @Test
    @DisplayName("存储键缺失时抛出业务异常进入重试")
    void shouldThrowWhenStorageKeyMissing() {
      when(knowledgeBaseRepository.findById(7L))
          .thenReturn(Optional.of(kb(" ", "a.pdf")));

      assertThatThrownBy(() -> consumer.processBusiness(payload()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("缺少存储信息");
      verify(vectorService, never()).vectorizeAndStore(anyLong(), anyString());
    }

    @Test
    @DisplayName("下载失败时异常向上抛出由模板重试")
    void shouldPropagateDownloadFailure() {
      when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(kb("kb/7", "a.pdf")));
      when(parseService.downloadAndParseContent("kb/7", "a.pdf"))
          .thenThrow(new BusinessException(interview.guide.common.exception.ErrorCode.INTERNAL_ERROR, "RustFS 不可用"));

      assertThatThrownBy(() -> consumer.processBusiness(payload()))
          .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("解析为空时抛出业务异常")
    void shouldThrowWhenParsedContentEmpty() {
      when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(kb("kb/7", "a.pdf")));
      when(parseService.downloadAndParseContent("kb/7", "a.pdf")).thenReturn("   ");
      when(knowledgeBaseRepository.heartbeatVectorProcessing(eq(7L), eq("attempt-1"), any()))
          .thenReturn(1);

      assertThatThrownBy(() -> consumer.processBusiness(payload()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("无法从文件中提取文本内容");
    }

    @Test
    @DisplayName("正常路径从 RustFS 下载解析后向量化")
    void shouldVectorizeDownloadedContent() {
      when(knowledgeBaseRepository.findById(7L)).thenReturn(Optional.of(kb("kb/7", "a.pdf")));
      when(parseService.downloadAndParseContent("kb/7", "a.pdf")).thenReturn("解析后的正文");
      when(knowledgeBaseRepository.heartbeatVectorProcessing(eq(7L), eq("attempt-1"), any()))
          .thenReturn(1);

      consumer.processBusiness(payload());

      verify(vectorService).vectorizeAndStore(org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("解析后的正文"),
            org.mockito.ArgumentMatchers.eq("attempt-1"),
            org.mockito.ArgumentMatchers.any(Runnable.class),
            org.mockito.ArgumentMatchers.argThat(source -> "a.pdf".equals(source.filename())));
    }
  }

  @Test
  @DisplayName("条件领取：tryMarkProcessing 走 PENDING→PROCESSING 条件更新")
  void conditionalClaim() {
    when(knowledgeBaseRepository.tryMarkVectorProcessing(eq(7L), anyString(), any()))
        .thenReturn(1);

    VectorizeStreamConsumer.VectorizePayload payload = new VectorizeStreamConsumer.VectorizePayload(7L);
    boolean claimed = consumer.tryMarkProcessing(payload);

    assertThat(claimed).isTrue();
    assertThat(payload.attemptId()).isNotBlank();
    verify(knowledgeBaseRepository).tryMarkVectorProcessing(eq(7L), eq(payload.attemptId()), any());
  }

  @Test
  @DisplayName("条件领取失败（他人已领取）：不执行任务")
  void conditionalClaimRejected() {
    when(knowledgeBaseRepository.tryMarkVectorProcessing(eq(7L), anyString(), any()))
        .thenReturn(0);

    boolean claimed = consumer.tryMarkProcessing(new VectorizeStreamConsumer.VectorizePayload(7L));

    assertThat(claimed).isFalse();
  }

  @Test
  @DisplayName("markCompleted/markFailed 走条件更新，非 PROCESSING 时由 SQL 层 no-op")
  void terminalStatesAreConditional() {
    consumer.markCompleted(payload());
    consumer.markFailed(payload(), "错误");

    verify(knowledgeBaseRepository).completeVectorIfProcessing(eq(7L), eq("attempt-1"), any());
    verify(knowledgeBaseRepository).failVectorIfProcessing(
        eq(7L), eq("attempt-1"), anyString(), any());
  }

  @Test
  @DisplayName("重试重置失败（任务已完成或被替代）：不重新投递，ACK 丢弃")
  void retrySkippedWhenResetFails() {
    when(knowledgeBaseRepository.resetVectorToPending(eq(7L), eq("attempt-1"), any()))
        .thenReturn(0);

    consumer.retryMessage(payload(), 2);

    verify(redisService, never()).streamAdd(anyString(), anyMap(), anyInt());
  }

  @Test
  @DisplayName("心跳节流：Embedding 阶段连续多批只写一次库（阈值内）")
  void heartbeatThrottledDuringEmbedding() {
    // 首批会写库一次（lastNanos 从 0 起步）
    when(knowledgeBaseRepository.heartbeatVectorProcessing(eq(7L), eq("attempt-1"), any()))
        .thenReturn(1);
    VectorizeStreamConsumer.VectorizePayload payload = payload();
    consumer.throttledEmbeddingHeartbeat(payload);
    // 节流窗口内的后续批次不再写库
    consumer.throttledEmbeddingHeartbeat(payload);
    consumer.throttledEmbeddingHeartbeat(payload);

    verify(knowledgeBaseRepository, org.mockito.Mockito.times(1))
        .heartbeatVectorProcessing(eq(7L), eq("attempt-1"), any());
  }

  @Test
  @DisplayName("重试消息只携带 kbId 与 retryCount")
  void shouldRetryWithIdOnlyMessage() {
    when(knowledgeBaseRepository.resetVectorToPending(eq(7L), eq("attempt-1"), any()))
        .thenReturn(1);
    consumer.retryMessage(payload(), 2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(redisService).streamAdd(anyString(), captor.capture(), anyInt());
    assertThat(captor.getValue())
        .containsEntry("kbId", "7")
        .containsEntry("retryCount", "2")
        .hasSize(2);
    assertThat(captor.getValue().keySet()).doesNotContain("content");
  }
}
