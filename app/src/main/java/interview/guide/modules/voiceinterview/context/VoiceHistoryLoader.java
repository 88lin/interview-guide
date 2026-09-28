package interview.guide.modules.voiceinterview.context;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音面试上下文有界加载器（P1-06）。
 *
 * <p>把"数据库加载 + 压缩编排"从 WebSocket Handler 移出，并保证数据库侧读取量有界：
 * 单次最多读取「最近窗口 + 一个摘要批次」，不再全量读取长会话历史。
 *
 * <p>与 {@link VoiceContextCompressor} 的契约：传入的 turns 是「pending 段 + 最近窗口」，
 * coveredTurns 恒为 0（pending 已在覆盖边界之后）；压缩成功后的新覆盖边界
 * = turns.get(compressed.coveredTurns() - 1).sequenceNum，锁内校验不回退。
 *
 * <p>旧 SUMMARY 行（新边界字段为空）：只用 OFFSET/LIMIT 1 定位第 N 条原始消息迁移边界，
 * 不做全量读取与重新摘要。contextCompression.enabled=false 时显式保留全量分支（仅本地排查）。
 */
@Slf4j
@Component
public class VoiceHistoryLoader {

  private final VoiceInterviewMessageRepository messageRepository;
  private final VoiceInterviewService interviewService;
  private final VoiceContextCompressor compressor;
  private final VoiceInterviewProperties properties;

  public VoiceHistoryLoader(VoiceInterviewMessageRepository messageRepository,
                            VoiceInterviewService interviewService,
                            VoiceContextCompressor compressor,
                            VoiceInterviewProperties properties) {
    this.messageRepository = messageRepository;
    this.interviewService = interviewService;
    this.compressor = compressor;
    this.properties = properties;
  }

  /**
   * 加载并压缩会话历史，返回格式化后的文本行（含可选的摘要行）。
   */
  public List<String> loadHistory(String sessionId, String llmProvider) {
    var cfg = properties.getContextCompression();
    if (!cfg.isEnabled()) {
      // 唯一的全量分支：enabled=false 仅用于本地排查，生产默认不得走进
      log.warn("上下文压缩已关闭，语音历史走全量读取（仅限本地排查）: sessionId={}", sessionId);
      return loadUncompressed(sessionId);
    }
    try {
      return loadBounded(sessionId, llmProvider, cfg);
    } catch (Exception e) {
      log.warn("语音历史有界加载失败，降级为最近窗口: sessionId={}, error={}",
          sessionId, e.getClass().getSimpleName(), e);
      return loadWindowOnly(sessionId);
    }
  }

  private List<String> loadBounded(String sessionId, String llmProvider,
                                   VoiceInterviewProperties.ContextCompressionConfig cfg) {
    Long sessionIdLong = Long.parseLong(sessionId);
    int windowSize = cfg.getWindowSize();
    int batchSize = cfg.getSummaryBatchSize();

    // 1. 读取 SUMMARY 行（单行查询）
    VoiceInterviewMessageEntity summaryRow = interviewService.loadSummaryRow(sessionId).orElse(null);
    String cachedSummary = summaryRow != null
        ? VoiceInterviewMessageEntity.trimToNull(summaryRow.getAiGeneratedText()) : null;
    Integer coveredSequenceNum = summaryRow != null
        ? summaryRow.getSummaryCoveredSequenceNum() : null;
    if (summaryRow != null && coveredSequenceNum == null) {
      // 2. 旧数据迁移：只用 OFFSET/LIMIT 1 定位第 N 条，不全量读取、不重新摘要
      coveredSequenceNum = migrateLegacyBoundary(sessionIdLong,
          summaryRow.getSequenceNum());
      interviewService.saveSummaryRow(sessionId, cachedSummary, coveredSequenceNum);
    }

    // 3. 倒序取最近窗口，内存恢复升序
    List<VoiceInterviewMessageEntity> recentDesc = messageRepository
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
            sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
            PageRequest.of(0, windowSize));
    List<VoiceInterviewMessageEntity> recent = new ArrayList<>(recentDesc);
    java.util.Collections.reverse(recent);

    // 4. 边界之后、最近窗口之前的待摘要段（升序，最多一个批次）。
    //    无 SUMMARY 行时 covered 取 0；每轮只推进一个批次，大量历史积压不能靠一次加载补齐。
    Integer windowMin = recent.isEmpty() ? null : recent.getFirst().getSequenceNum();
    List<VoiceInterviewMessageEntity> pending = List.of();
    int after = coveredSequenceNum != null ? coveredSequenceNum : 0;
    // 窗口未填满时必然没有窗口外的未摘要消息，直接跳过 pending 查询
    if (windowMin != null && recent.size() >= windowSize && windowMin > after) {
      pending = messageRepository
          .findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
              sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
              after, windowMin, PageRequest.of(0, batchSize));
    }

    // 5. 压缩：turns = pending + 窗口，coveredTurns 恒为 0（换算在 Loader 完成）
    List<VoiceInterviewMessageEntity> turns = new ArrayList<>(pending);
    turns.addAll(recent);
    var compressed = compressor.compress(turns, cachedSummary, 0, llmProvider);

    // 6. 持久化推进后的边界（LLM 已在 compress 内完成，事务在 saveSummaryRow 内）
    if (compressed.changed() && compressed.summary() != null && !compressed.summary().isBlank()
        && compressed.coveredTurns() > 0) {
      int newBoundary = turns.get(compressed.coveredTurns() - 1).getSequenceNum();
      try {
        interviewService.saveSummaryRow(sessionId, compressed.summary(), newBoundary);
      } catch (Exception e) {
        log.warn("持久化上下文摘要失败（不影响本次应答），session {}", sessionId, e);
      }
    }

    List<String> history = new ArrayList<>();
    if (compressed.summary() != null && !compressed.summary().isBlank()) {
      history.add("【对话摘要】" + compressed.summary());
    }
    history.addAll(compressor.formatRecent(compressed.recent()));
    return history;
  }

  /**
   * 旧 SUMMARY 行迁移：coveredTurns = -oldSequenceNum - 1，
   * 用 OFFSET/LIMIT 1 定位第 N 条原始消息的 sequenceNum 作为边界并写回。
   */
  private Integer migrateLegacyBoundary(Long sessionIdLong, Integer legacySequenceNum) {
    int coveredTurns = legacySequenceNum != null
        ? Math.max(0, -legacySequenceNum - 1) : 0;
    if (coveredTurns <= 0) {
      return 0;
    }
    VoiceInterviewMessageEntity nth = messageRepository
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
            sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
            PageRequest.of(coveredTurns - 1, 1))
        .stream()
        .findFirst()
        .orElse(null);
    if (nth == null || nth.getSequenceNum() == null) {
      log.warn("旧摘要边界迁移定位失败，回退边界 0: sessionId={}", sessionIdLong);
      return 0;
    }
    return nth.getSequenceNum();
  }

  /**
   * 摘要失败/加载异常时的降级：只取最近窗口（查询量仍有界），不恢复全量。
   */
  private List<String> loadWindowOnly(String sessionId) {
    Long sessionIdLong = Long.parseLong(sessionId);
    int windowSize = properties.getContextCompression().getWindowSize();
    List<VoiceInterviewMessageEntity> recentDesc = messageRepository
        .findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
            sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY,
            PageRequest.of(0, windowSize));
    List<VoiceInterviewMessageEntity> recent = new ArrayList<>(recentDesc);
    java.util.Collections.reverse(recent);
    return new ArrayList<>(compressor.formatRecentWithinBudget(recent));
  }

  /**
   * enabled=false 的全量分支（仅本地排查）：行为与旧 getHistory 全量路径一致。
   */
  private List<String> loadUncompressed(String sessionId) {
    List<VoiceInterviewMessageEntity> turns = interviewService.getConversationHistory(sessionId);
    return new ArrayList<>(compressor.formatRecent(turns));
  }
}
