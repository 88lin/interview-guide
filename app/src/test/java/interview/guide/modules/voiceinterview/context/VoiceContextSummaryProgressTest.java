package interview.guide.modules.voiceinterview.context;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.domain.Pageable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("真实压缩器与有界 Loader 的摘要进度")
class VoiceContextSummaryProgressTest {

  @Mock
  private LlmProviderRegistry registry;
  @Mock
  private VoiceInterviewMessageRepository repository;
  @Mock
  private VoiceInterviewService service;

  private VoiceInterviewProperties properties;
  private VoiceContextCompressor compressor;
  private VoiceHistoryLoader loader;

  @BeforeEach
  void setUp() {
    properties = new VoiceInterviewProperties();
    compressor = new VoiceContextCompressor(registry, properties, new DefaultResourceLoader());
    loader = new VoiceHistoryLoader(repository, service, compressor, properties);
  }

  @Test
  @DisplayName("摘要追上窗口后，下一次加载仍将旧摘要放入 Prompt")
  void caughtUpSummaryRemainsInPrompt() {
    stubHistory(List.of());

    List<String> history = loader.loadHistory("1", null);

    assertThat(history.getFirst()).isEqualTo("【对话摘要】已有摘要");
    assertThat(String.join("\n", history)).contains("问题31", "问题50");
    verify(service, never()).saveSummaryRow(anyString(), anyString(), anyInt());
    verifyNoInteractions(registry);
  }

  @Test
  @DisplayName("摘要正文未变也保存已处理的新边界，下一轮不重复摘要同一批")
  void identicalSummaryStillAdvancesBoundary() {
    stubHistory(messages(21, 30));
    ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(registry.getPlainChatClient()).thenReturn(client);
    when(client.prompt().user(anyString()).call().content()).thenReturn("已有摘要");

    List<String> history = loader.loadHistory("1", null);

    verify(service).saveSummaryRow("1", "已有摘要", 30);
    assertThat(history.getFirst()).isEqualTo("【对话摘要】已有摘要");
    assertThat(String.join("\n", history)).contains("问题31").doesNotContain("问题21");
  }

  @Test
  @DisplayName("窗口以内复用旧摘要时，仍执行摘要和历史字符预算")
  void cachedSummaryWithinWindowRespectsBudgets() {
    properties.getContextCompression().setMaxSummaryChars(100);
    properties.getContextCompression().setMaxHistoryChars(200);

    var result = compressor.compress(messages(1, 15), "摘要".repeat(100), 0);

    assertThat(result.summary()).hasSize(100).endsWith("(摘要已截断)");
    int historyChars = compressor.formatRecent(result.recent()).stream()
        .mapToInt(String::length).sum();
    assertThat(result.summary().length() + historyChars).isLessThanOrEqualTo(200);
    assertThat(result.recent().getLast().getSequenceNum()).isEqualTo(15);
    assertThat(result.changed()).isFalse();
    verifyNoInteractions(registry);
  }

  @Test
  @DisplayName("切换 WINDOW 模式后不再携带先前 SUMMARY 模式的摘要")
  void windowModeIgnoresCachedSummary() {
    properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.WINDOW);

    var result = compressor.compress(messages(1, 30), "已有摘要", 0);

    assertThat(result.summary()).isNull();
    assertThat(result.recent()).hasSize(20);
    assertThat(result.recent().getFirst().getSequenceNum()).isEqualTo(11);
    verifyNoInteractions(registry);
  }

  private void stubHistory(List<VoiceInterviewMessageEntity> pending) {
    int boundary = pending.isEmpty() ? 30 : 20;
    var summary = VoiceInterviewMessageEntity.builder()
        .sequenceNum(-1).messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
        .aiGeneratedText("已有摘要").summaryCoveredSequenceNum(boundary).build();
    when(service.loadSummaryRow("1")).thenReturn(Optional.of(summary));
    when(repository.findBySessionIdAndMessageTypeNotOrderBySequenceNumDesc(
        eq(1L), anyString(), any(Pageable.class)))
        .thenReturn(messages(31, 50).stream()
            .sorted(Comparator.comparing(VoiceInterviewMessageEntity::getSequenceNum).reversed()).toList());
    when(repository.findBySessionIdAndMessageTypeNotAndSequenceNumGreaterThanAndSequenceNumLessThanOrderBySequenceNumAsc(
        eq(1L), anyString(), eq(boundary), eq(31), any(Pageable.class))).thenReturn(pending);
  }

  private List<VoiceInterviewMessageEntity> messages(int first, int last) {
    return IntStream.rangeClosed(first, last)
        .mapToObj(sequence -> VoiceInterviewMessageEntity.builder()
            .sequenceNum(sequence).messageType("AI_SPEECH")
            .aiGeneratedText("问题" + sequence).userRecognizedText("回答" + sequence).build())
        .toList();
  }
}
