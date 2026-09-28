package interview.guide.modules.voiceinterview.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.ApiPathResolver;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.json.JsonMapper;

/** 仅显式运行：不读取业务数据、不启动数据库，实际付费调用最多正常路径 7 次。 */
@Tag("voice-context-eval")
@EnabledIfEnvironmentVariable(named = "RUN_VOICE_CONTEXT_EVAL", matches = "true")
class VoiceContextEvaluationTest {
  @Test
  @DisplayName("虚构六十轮会话：记录真实摘要成本及三组交替顺序的生成对照")
  void compareLongHistory() throws Exception {
    String key = System.getenv("AI_BAILIAN_API_KEY");
    assertThat(key).as("需要 AI_BAILIAN_API_KEY（不要将值写入报告）").isNotBlank();
    String modelName = System.getenv().getOrDefault("AI_MODEL", "qwen3.5-flash");
    var api = ApiPathResolver.buildOpenAiClient(
        "https://dashscope.aliyuncs.com/compatible-mode/v1", key, 10000, 90000);
    var delegate = OpenAiChatModel.builder().openAiClient(api).openAiClientAsync(api.async())
        .options(OpenAiChatOptions.builder().model(modelName).temperature(0.2).build()).build();
    List<Map<String, Object>> calls = new ArrayList<>();
    ChatModel recording = new ChatModel() {
      @Override
      public ChatOptions getOptions() {
        return delegate.getOptions();
      }

      @Override
      public ChatResponse call(Prompt prompt) {
        if (calls.isEmpty()) {
          assertThat(prompt.getContents()).contains("这一轮重点是", "第1轮", "第40轮")
              .doesNotContain("<newTurns>", "<previousSummary>");
        }
        long start = System.nanoTime();
        ChatResponse response = delegate.call(prompt);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("latencyMs", (System.nanoTime() - start) / 1_000_000.0);
        var usage = response.getMetadata().getUsage();
        call.put("promptTokens", usage == null ? null : usage.getPromptTokens());
        call.put("completionTokens", usage == null ? null : usage.getCompletionTokens());
        call.put("totalTokens", usage == null ? null : usage.getTotalTokens());
        call.put("responseModel", response.getMetadata().getModel());
        call.put("requestChars", prompt.getContents().length());
        call.put("requestMd5", DigestUtils.md5DigestAsHex(prompt.getContents().getBytes(StandardCharsets.UTF_8)));
        calls.add(call);
        return response;
      }
    };
    ChatClient client = ChatClient.builder(recording).build();
    LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    when(registry.getPlainChatClient()).thenReturn(client);
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    var compressor = new VoiceContextCompressor(registry, properties, new DefaultResourceLoader());
    List<VoiceInterviewMessageEntity> turns = fixture();
    String before = String.join("\n", compressor.formatRecent(turns));
    long start = System.nanoTime();
    var compressed = compressor.compress(turns, null, 0);
    double compressionMs = (System.nanoTime() - start) / 1_000_000.0;
    assertThat(compressed.changed()).as("必须真实生成摘要，不能用降级结果充当 SUMMARY 对照").isTrue();
    assertThat(calls).hasSize(1);
    calls.getFirst().put("phase", "summary");
    List<String> afterParts = new ArrayList<>();
    afterParts.add("【对话摘要】" + compressed.summary());
    afterParts.addAll(compressor.formatRecent(compressed.recent()));
    String after = String.join("\n", afterParts);
    assertThat(after.length()).isLessThan(before.length());
    for (int pair = 1; pair <= 3; pair++) {
      for (String variant : pair % 2 == 1 ? List.of("before", "after") : List.of("after", "before")) {
        String answer = client.prompt().system("你是 Java 技术面试官。根据对话历史提出一个不重复的追问，只输出问题，不超过80个汉字。")
            .user("以下是虚构会话历史：\n" + (variant.equals("before") ? before : after))
            .call().content();
        assertThat(answer).isNotBlank();
        calls.getLast().put("phase", variant);
        calls.getLast().put("pair", pair);
      }
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("runAt", Instant.now().toString());
    report.put("scope", "synthetic-v1, real provider; not production E2E or answer quality evaluation");
    report.put("model", modelName);
    report.put("temperature", 0.2);
    report.put("turns", turns.size());
    report.put("fixtureMd5", DigestUtils.md5DigestAsHex(before.getBytes(StandardCharsets.UTF_8)));
    report.put("summaryPromptMd5", DigestUtils.md5DigestAsHex(new DefaultResourceLoader()
        .getResource("classpath:prompts/voice-interview-context-summary.st").getContentAsByteArray()));
    report.put("config", Map.of("mode", "SUMMARY", "windowSize", 20, "summaryBatchSize", 10,
        "maxHistoryChars", 12000, "maxSummaryChars", 4000));
    report.put("beforeHistoryChars", before.length());
    report.put("afterHistoryChars", after.length());
    report.put("charDefinition", "VoiceHistoryLoader format including summary label and newline separators");
    report.put("compressionMs", compressionMs);
    report.put("retainedTurns", compressed.recent().size());
    report.put("coveredTurns", compressed.coveredTurns());
    report.put("calls", calls);
    Path dir = Path.of(System.getProperty("voiceEval.reportDir", "build/reports/voice-context-eval"));
    Files.createDirectories(dir);
    String id = "voice-context-" + System.currentTimeMillis();
    Files.writeString(dir.resolve(id + ".json"), JsonMapper.builder().build()
        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
    StringBuilder md = new StringBuilder("# 语音上下文压缩真实模型对照\n\n")
        .append("虚构 60 轮；同一模型与温度，3 组交替顺序。无 ASR/TTS/数据库，不代表生产延迟或答案质量。\n\n")
        .append("历史字符：").append(before.length()).append(" → ").append(after.length())
        .append("；摘要耗时：").append(compressionMs).append(" ms。\n\n")
        .append("| 阶段 | 组 | 耗时 ms | 输入 Token | 输出 Token | 总 Token |\n|---|---|---|---|---|---|\n");
    for (var call : calls) {
      md.append("| ").append(call.get("phase")).append(" | ").append(call.getOrDefault("pair", "—"))
          .append(" | ").append(call.get("latencyMs")).append(" | ").append(call.get("promptTokens"))
          .append(" | ").append(call.get("completionTokens")).append(" | ").append(call.get("totalTokens")).append(" |\n");
    }
    md.append("\n摘要为一次冷启动成本，应另加到触发摘要的那一轮；后续复用不重复付费。不可把单轮输入减少等同总成本减少。\n");
    Files.writeString(dir.resolve(id + ".md"), md);
  }

  private List<VoiceInterviewMessageEntity> fixture() {
    List<VoiceInterviewMessageEntity> turns = new ArrayList<>();
    String[] topics = {"Redis 消息幂等", "数据库事务边界", "线程池背压", "索引选择", "缓存一致性", "任务代次隔离"};
    for (int i = 0; i < 60; i++) {
      turns.add(VoiceInterviewMessageEntity.builder().sequenceNum(i + 1)
          .aiGeneratedText("第" + (i + 1) + "轮：请解释虚构项目中的" + topics[i % topics.length] + "，包括异常与重试设计。")
          .userRecognizedText(("我负责虚构订单服务，先界定数据一致性边界，再设计失败恢复。"
              + "我们用数据库条件更新保护状态迁移，以任务代次拒绝旧结果，外部请求放在事务之外。"
              + "重试需考虑幂等与退避；故障时保留可观测的错误类型，不记录用户原文。"
              + "压测分别检查吞吐、尾延迟和数据库连接占用，不能只看平均耗时。")
              .repeat(4) + "这一轮重点是" + topics[i % topics.length] + "。")
          .build());
    }
    return turns;
  }
}
