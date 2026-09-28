package interview.guide.modules.voiceinterview.context;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * VoiceContextCompressor 单元测试。
 *
 * <p>覆盖：happy path（关闭/窗口/摘要触发）、边界（空输入、未达批次数）、
 * 兼容性（关闭时格式化与改前一致）、LLM 摘要失败降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VoiceContextCompressor 测试")
class VoiceContextCompressorTest {

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    private VoiceInterviewProperties properties;
    private VoiceContextCompressor compressor;

    @BeforeEach
    void setUp() {
        properties = new VoiceInterviewProperties();
        properties.setContextCompression(new VoiceInterviewProperties.ContextCompressionConfig());
        compressor = new VoiceContextCompressor(
            llmProviderRegistry, properties, new DefaultResourceLoader());
    }

    private VoiceInterviewMessageEntity turn(int seq, String ai, String user) {
        return VoiceInterviewMessageEntity.builder()
                .sequenceNum(seq)
                .aiGeneratedText(ai)
                .userRecognizedText(user)
                .build();
    }

    @Test
    @DisplayName("真实摘要模板注入旧摘要和新增轮次，不把占位符原文发送给模型")
    void summaryPromptContainsActualHistory() {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(llmProviderRegistry.getPlainChatClient()).thenReturn(client);
        when(client.prompt().user(anyString()).call().content()).thenReturn("合并后的摘要");
        var result = compressor.compress(turns(35), "旧摘要中的项目事实", 0);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).user(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(prompt -> assertThat(prompt)
            .contains("旧摘要中的项目事实", "面试官问题0", "候选人回答14")
            .doesNotContain("<previousSummary>", "<newTurns>", "{previousSummary}", "{newTurns}", "候选人回答15"));
        assertThat(result.summary()).isEqualTo("合并后的摘要");
    }

    private List<VoiceInterviewMessageEntity> turns(int n) {
        List<VoiceInterviewMessageEntity> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(turn(i + 1, "面试官问题" + i, "候选人回答" + i));
        }
        return list;
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("关闭时返回全量，且与 formatRecent 等价（向后兼容）")
        void disabledReturnsAll() {
            properties.getContextCompression().setEnabled(false);
            List<VoiceInterviewMessageEntity> all = turns(30);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertNull(r.summary());
            assertFalse(r.changed());
            assertEquals(30, r.recent().size());
            verify(llmProviderRegistry, never()).getPlainChatClient();
        }

        @Test
        @DisplayName("WINDOW 模式：仅保留最近 windowSize 轮，不调用 LLM")
        void windowMode() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.WINDOW);
            properties.getContextCompression().setWindowSize(20);
            List<VoiceInterviewMessageEntity> all = turns(30);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertNull(r.summary());
            assertFalse(r.changed());
            assertEquals(20, r.recent().size());
            verify(llmProviderRegistry, never()).getPlainChatClient();
        }

        @Test
        @DisplayName("SUMMARY 模式 + 达到批次数：触发增量摘要，changed=true")
        void summaryTriggered() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setSummaryBatchSize(10);
            // total=35 → earlyCount=15 >= 10 → 触发
            List<VoiceInterviewMessageEntity> all = turns(35);
            ChatClient chatClient = mockChatClient("合并后的摘要");

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertEquals("合并后的摘要", r.summary());
            assertTrue(r.changed());
            assertEquals(15, r.coveredTurns());
            assertEquals(20, r.recent().size());
            verify(llmProviderRegistry, times(1)).getPlainChatClient();
        }

        @Test
        @DisplayName("SUMMARY 模式使用会话选择的 LLM 提供商")
        void summaryUsesSessionProvider() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setSummaryBatchSize(10);
            List<VoiceInterviewMessageEntity> all = turns(35);
            ChatClient chatClient = mockChatClient("glm", "合并后的摘要");

            VoiceContextCompressor.CompressedHistory result =
                compressor.compress(all, null, 0, "glm");

            assertEquals("合并后的摘要", result.summary());
            verify(llmProviderRegistry).getPlainChatClient("glm");
            verify(llmProviderRegistry, never()).getPlainChatClient();
        }
    }

    @Nested
    @DisplayName("边界情况")
    class Boundary {

        @Test
        @DisplayName("空输入：返回空，不报错")
        void emptyInput() {
            VoiceContextCompressor.CompressedHistory r = compressor.compress(List.of(), null, 0);
            assertNull(r.summary());
            assertFalse(r.changed());
            assertTrue(r.recent().isEmpty());
        }

        @Test
        @DisplayName("未达窗口大小：返回全量，不压缩")
        void belowWindow() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            List<VoiceInterviewMessageEntity> all = turns(15);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertNull(r.summary());
            assertFalse(r.changed());
            assertEquals(15, r.recent().size());
        }

        @Test
        @DisplayName("SUMMARY 模式但未达批次数：不调用 LLM，保留所有尚未被摘要覆盖的轮次")
        void summaryNotTriggered() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setSummaryBatchSize(10);
            // total=25 → earlyCount=5 < 10 → 不触发
            List<VoiceInterviewMessageEntity> all = turns(25);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, "已有摘要", 0);

            assertEquals("已有摘要", r.summary());
            assertFalse(r.changed());
            assertEquals(25, r.recent().size());
            assertEquals(1, r.recent().getFirst().getSequenceNum());
            verify(llmProviderRegistry, never()).getPlainChatClient();
        }

        @Test
        @DisplayName("SUMMARY 模式但 coveredTurns 大于 earlyCount（脏数据）：前置条件拦截，跳过摘要且不抛异常")
        void dirtyCoveredTurnsSkipsSafely() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setSummaryBatchSize(10);
            // total=35 → earlyCount=15，但传入脏数据 coveredTurns=20（> earlyCount）
            List<VoiceInterviewMessageEntity> all = turns(35);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, "已有摘要", 20);

            // earlyCount > coveredTurns 前置条件拦住，subList 不会收到 from > to，不会抛 IllegalArgumentException
            assertEquals("已有摘要", r.summary());
            assertFalse(r.changed());
            assertEquals(20, r.recent().size());
            verify(llmProviderRegistry, never()).getPlainChatClient();
        }
    }

    @Nested
    @DisplayName("LLM 失败降级 & 兼容性")
    class FailureAndCompat {

        @Test
        @DisplayName("摘要生成抛异常且有旧摘要：保留旧摘要并限制为最近窗口")
        void summaryFailureFallbackKeepsOldSummaryAndWindow() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setSummaryBatchSize(10);
            List<VoiceInterviewMessageEntity> all = turns(35);
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt()).thenReturn(spec);
            when(spec.user(anyString())).thenReturn(spec);
            when(spec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenThrow(new RuntimeException("llm down"));
            when(llmProviderRegistry.getPlainChatClient()).thenReturn(chatClient);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, "已有摘要", 0);

            // 失败降级：保留旧摘要 + 最近窗口，不允许恢复为全量历史
            assertEquals("已有摘要", r.summary());
            assertFalse(r.changed());
            assertEquals(20, r.recent().size());
            assertEquals(16, r.recent().getFirst().getSequenceNum());
        }

        @Test
        @DisplayName("首次摘要生成失败且无旧摘要：降级为 WINDOW，只返回最近窗口")
        void firstSummaryFailureDegradesToWindow() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setSummaryBatchSize(10);
            List<VoiceInterviewMessageEntity> all = turns(35);
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            when(chatClient.prompt()).thenReturn(spec);
            when(spec.user(anyString())).thenReturn(spec);
            when(spec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenThrow(new RuntimeException("llm down"));
            when(llmProviderRegistry.getPlainChatClient()).thenReturn(chatClient);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertNull(r.summary());
            assertFalse(r.changed());
            assertEquals(20, r.recent().size());
            assertEquals(16, r.recent().getFirst().getSequenceNum());
        }

        @Test
        @DisplayName("formatRecent 与原 getHistory 格式化一致：AI+用户成对、孤立 AI 暂挂起")
        void formatRecentMatchesLegacy() {
            List<VoiceInterviewMessageEntity> all = new ArrayList<>();
            all.add(turn(1, "你介绍一下项目", "做过订单系统"));
            all.add(turn(2, "用了什么数据库", null));      // 孤立 AI：挂起
            all.add(turn(3, null, "MySQL 和 Redis"));        // 用户回答，配对上一条 AI
            all.add(turn(4, "为什么选 Redis", "因为缓存热数据"));

            List<String> formatted = compressor.formatRecent(all);

            assertEquals(List.of(
                    "面试官：你介绍一下项目",
                    "候选人：做过订单系统",
                    "面试官：用了什么数据库",
                    "候选人：MySQL 和 Redis",
                    "面试官：为什么选 Redis",
                    "候选人：因为缓存热数据"
            ), formatted);
        }
    }

    @Nested
    @DisplayName("字符硬预算与配置校验（P0-05）")
    class CharBudgetAndValidation {

        private void enableSummary(int maxHistoryChars, int maxSummaryChars) {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.SUMMARY);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setSummaryBatchSize(10);
            properties.getContextCompression().setMaxHistoryChars(maxHistoryChars);
            properties.getContextCompression().setMaxSummaryChars(maxSummaryChars);
        }

        private int formattedChars(String summary, List<VoiceInterviewMessageEntity> recent) {
            int chars = summary != null ? summary.length() : 0;
            for (String line : compressor.formatRecent(recent)) {
                chars += line.length();
            }
            return chars;
        }

        @Test
        @DisplayName("5 轮但单轮超长：未达窗口仍受字符预算约束")
        void longSingleTurnRespectsBudget() {
            enableSummary(500, 300);
            List<VoiceInterviewMessageEntity> all = new ArrayList<>();
            all.add(turn(1, "问题".repeat(400), "回答".repeat(400)));
            for (int i = 2; i <= 5; i++) {
                all.add(turn(i, "短问题" + i, "短回答" + i));
            }

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertTrue(formattedChars(r.summary(), r.recent()) <= 500);
            // 始终保留最近消息
            assertEquals(5, r.recent().getLast().getSequenceNum());
        }

        @Test
        @DisplayName("最近窗口降级格式化仍受字符硬预算约束")
        void fallbackWindowFormattingRespectsBudget() {
            enableSummary(120, 80);
            List<String> history = compressor.formatRecentWithinBudget(List.of(
                turn(1, "早期问题".repeat(40), "早期回答".repeat(40)),
                turn(2, "最近问题".repeat(40), "最近回答".repeat(40))));

            assertTrue(history.stream().mapToInt(String::length).sum() <= 120);
            assertThat(history).anySatisfy(line -> assertThat(line).contains("最近回答"));
        }

        @Test
        @DisplayName("35 轮且摘要成功：摘要 + 最近窗口不超过预算")
        void summaryPlusWindowWithinBudget() {
            enableSummary(5000, 4000);
            List<VoiceInterviewMessageEntity> all = turns(35);
            mockChatClient("摘要".repeat(100));

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertEquals("摘要".repeat(100), r.summary());
            assertTrue(formattedChars(r.summary(), r.recent()) <= 5000);
            assertEquals(35, r.recent().getLast().getSequenceNum());
        }

        @Test
        @DisplayName("摘要 3900 字 + 近期 9000 字：裁剪近期消息，摘要不被裁")
        void budgetTrimsRecentKeepsSummary() {
            enableSummary(12000, 4000);
            // 35 轮：前 15 轮进入摘要，后 20 轮各约 460 字 → 近期约 9200 字
            List<VoiceInterviewMessageEntity> all = new ArrayList<>();
            for (int i = 1; i <= 35; i++) {
                if (i <= 15) {
                    all.add(turn(i, "短问题" + i, "短回答" + i));
                } else {
                    all.add(turn(i, "问".repeat(230), "答".repeat(230)));
                }
            }
            String summary = "摘".repeat(3900);
            mockChatClient(summary);

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            // 摘要未超 maxSummaryChars，不应被截断
            assertEquals(summary, r.summary());
            assertTrue(formattedChars(r.summary(), r.recent()) <= 12000);
            // 预算 12000 - 3900 = 8100，每轮约 470 字，最多保留 17 轮
            assertTrue(r.recent().size() <= 17);
            assertEquals(35, r.recent().getLast().getSequenceNum());
        }

        @Test
        @DisplayName("摘要超过 maxSummaryChars：从尾部截断并附加标记")
        void summaryTruncatedWithMarker() {
            enableSummary(5000, 100);
            List<VoiceInterviewMessageEntity> all = turns(35);
            mockChatClient("摘".repeat(300));

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            assertThatSummaryTruncated(r.summary());
        }

        private void assertThatSummaryTruncated(String summary) {
            assertNotNull(summary);
            assertTrue(summary.length() <= 100);
            assertTrue(summary.endsWith("(摘要已截断)"));
        }

        @Test
        @DisplayName("WINDOW 模式：不调用 LLM，字符预算仍生效")
        void windowModeAppliesCharBudget() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.WINDOW);
            properties.getContextCompression().setWindowSize(20);
            properties.getContextCompression().setMaxHistoryChars(1000);
            List<VoiceInterviewMessageEntity> all = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                all.add(turn(i, "问".repeat(100), "答".repeat(100)));
            }

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            verify(llmProviderRegistry, never()).getPlainChatClient();
            assertTrue(formattedChars(null, r.recent()) <= 1000);
            assertEquals(30, r.recent().getLast().getSequenceNum());
        }

        @Test
        @DisplayName("enabled=true + NONE：不摘要不裁窗口，超预算时从最早消息删除")
        void noneModeStillAppliesCharBudget() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.NONE);
            properties.getContextCompression().setMaxHistoryChars(1000);
            List<VoiceInterviewMessageEntity> all = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                all.add(turn(i, "问".repeat(100), "答".repeat(100)));
            }

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            verify(llmProviderRegistry, never()).getPlainChatClient();
            assertNull(r.summary());
            assertTrue(r.recent().size() < 30);
            assertTrue(formattedChars(null, r.recent()) <= 1000);
            assertEquals(30, r.recent().getLast().getSequenceNum());
        }

        @Test
        @DisplayName("最近一条消息单独超预算时保留身份并截断文本，总字符不超过预算")
        void alwaysKeepsMostRecentMessageWithinBudget() {
            properties.getContextCompression().setEnabled(true);
            properties.getContextCompression().setMode(VoiceInterviewProperties.Mode.WINDOW);
            properties.getContextCompression().setWindowSize(3);
            properties.getContextCompression().setMaxHistoryChars(50);
            List<VoiceInterviewMessageEntity> all = List.of(
                turn(1, "短问题", "短回答"),
                turn(2, "短问题", "短回答"),
                turn(3, "超".repeat(500), "长".repeat(500)));

            VoiceContextCompressor.CompressedHistory r = compressor.compress(all, null, 0);

            // 保留最近消息身份（sequenceNum=3），且总字符仍受硬预算约束
            assertFalse(r.recent().isEmpty());
            assertEquals(3, r.recent().getLast().getSequenceNum());
            List<String> lines = compressor.formatRecent(r.recent());
            int total = lines.stream().mapToInt(String::length).sum();
            assertTrue(total <= 50, "格式化后总字符 " + total + " 应不超过预算 50");
            // 原实体未被修改
            assertEquals(500, all.get(2).getAiGeneratedText().length());
        }

        @Test
        @DisplayName("重连：已有摘要覆盖全部早期轮次时不重复摘要")
        void reconnectReusesPersistedSummary() {
            enableSummary(12000, 4000);
            List<VoiceInterviewMessageEntity> all = turns(35);

            VoiceContextCompressor.CompressedHistory r =
                compressor.compress(all, "已有摘要", 15);

            verify(llmProviderRegistry, never()).getPlainChatClient();
            assertEquals("已有摘要", r.summary());
            assertFalse(r.changed());
            assertEquals(20, r.recent().size());
        }

        @Test
        @DisplayName("配置非法：校验器指出具体字段")
        void invalidConfigFailsValidation() {
            jakarta.validation.Validator validator = jakarta.validation.Validation
                .buildDefaultValidatorFactory().getValidator();
            VoiceInterviewProperties.ContextCompressionConfig cfg =
                new VoiceInterviewProperties.ContextCompressionConfig();
            cfg.setEnabled(true);
            cfg.setWindowSize(0);
            cfg.setSummaryBatchSize(0);
            cfg.setMaxHistoryChars(100);
            cfg.setMaxSummaryChars(4000);
            cfg.setMode(VoiceInterviewProperties.Mode.SUMMARY);

            var violations = validator.validate(cfg);

            java.util.Set<String> messages = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(messages.stream().anyMatch(m -> m.contains("windowSize")));
            assertTrue(messages.stream().anyMatch(m -> m.contains("summaryBatchSize")));
            assertTrue(messages.stream().anyMatch(m -> m.contains("maxHistoryChars")
                && m.contains("大于等于")));
            assertTrue(messages.stream().anyMatch(m -> m.contains("maxSummaryChars")));
        }
    }

    private ChatClient mockChatClient(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(content);
        when(llmProviderRegistry.getPlainChatClient()).thenReturn(chatClient);
        return chatClient;
    }

    private ChatClient mockChatClient(String provider, String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(content);
        when(llmProviderRegistry.getPlainChatClient(provider)).thenReturn(chatClient);
        return chatClient;
    }
}
