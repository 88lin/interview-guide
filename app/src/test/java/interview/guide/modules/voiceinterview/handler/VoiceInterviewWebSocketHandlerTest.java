package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceInterviewWebSocketHandlerTest {

  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private QwenAsrService sttService;
  @Mock
  private QwenTtsService ttsService;
  @Mock
  private DashscopeLlmService llmService;
  @Mock
  private VoiceInterviewService interviewService;
  @Mock
  private ObjectProvider<MeterRegistry> meterRegistryProvider;

  private VoiceInterviewWebSocketHandler handler;

  @AfterEach
  void tearDown() {
    if (handler != null) {
      handler.destroy();
    }
  }

  @Test
  @DisplayName("默认关闭开场音频预热，应用启动时不调用云端 TTS")
  void shouldNotWarmupOpeningAudioByDefault() throws InterruptedException {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    CountDownLatch ttsCalled = new CountDownLatch(1);
    lenient().when(ttsService.synthesize(anyString())).thenAnswer(invocation -> {
      ttsCalled.countDown();
      return new byte[0];
    });
    handler = newHandler(properties);

    assertThat(properties.isOpeningAudioWarmupEnabled()).isFalse();

    handler.warmupOpeningAudioCache();

    assertThat(ttsCalled.await(300, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  @DisplayName("显式开启开场音频预热后才调用云端 TTS")
  void shouldWarmupOpeningAudioWhenExplicitlyEnabled() throws InterruptedException {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.setOpeningAudioWarmupEnabled(true);
    properties.getOpening().setSkillQuestions(Map.of("java-backend", "你好，开始面试。"));
    properties.getOpening().setAlgorithmQuestion("");
    properties.getOpening().setBackendQuestion("");
    CountDownLatch ttsCalled = new CountDownLatch(1);
    when(ttsService.synthesize("你好，开始面试。")).thenAnswer(invocation -> {
      ttsCalled.countDown();
      return new byte[0];
    });
    handler = newHandler(properties);

    handler.warmupOpeningAudioCache();

    assertThat(ttsCalled.await(1, TimeUnit.SECONDS)).isTrue();
  }

  private VoiceInterviewWebSocketHandler newHandler(VoiceInterviewProperties properties) {
    return new VoiceInterviewWebSocketHandler(
        objectMapper,
        sttService,
        ttsService,
        llmService,
        interviewService,
        properties,
        meterRegistryProvider
    );
  }
}
