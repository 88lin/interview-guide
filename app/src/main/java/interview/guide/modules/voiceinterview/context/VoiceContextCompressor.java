package interview.guide.modules.voiceinterview.context;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音面试上下文压缩器。
 *
 * <p>将全量对话历史压缩为「最近窗口原文 + 早期轮次滚动摘要」，避免长会话下
 * 每轮把完整转录重发给 LLM 导致的 prompt token 无界增长与上下文溢出风险。
 *
 * <p>设计要点（详见 docs/语音面试上下文压缩_技术方案设计.md）：
 * <ul>
 *   <li>mode=NONE：不压缩，返回全部（默认行为，向后兼容）</li>
 *   <li>mode=WINDOW：仅保留最近 windowSize 轮原文，更早轮次丢弃</li>
 *   <li>mode=SUMMARY：保留最近窗口原文 + 早期轮次增量摘要（按 summaryBatchSize 触发，降低 LLM 摘要调用频率）</li>
 * </ul>
 */
@Slf4j
@Component
public class VoiceContextCompressor {

    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewProperties properties;

    public VoiceContextCompressor(LlmProviderRegistry llmProviderRegistry, VoiceInterviewProperties properties) {
        this.llmProviderRegistry = llmProviderRegistry;
        this.properties = properties;
    }

    /**
     * 压缩对话历史。
     *
     * @param turns         全部对话轮次（不含 SUMMARY 行），按 sequenceNum 升序
     * @param cachedSummary 持久化已有的摘要（可能为 null）
     * @param coveredTurns  已被摘要覆盖的轮次数（用于增量合并，避免重复摘要）
     * @return 压缩结果：summary（可能为 null）、recent（保留的近期轮次）、coveredTurns、changed
     */
    public CompressedHistory compress(List<VoiceInterviewMessageEntity> turns,
                                       String cachedSummary, int coveredTurns) {
        var cfg = properties.getContextCompression();
        // 未启用 / NONE 模式 / 未达到窗口大小：不压缩，返回全量（向后兼容）
        if (!cfg.isEnabled() || cfg.getMode() == VoiceInterviewProperties.Mode.NONE
                || turns.size() <= cfg.getWindowSize()) {
            return new CompressedHistory(null, turns, turns.size(), false);
        }

        int total = turns.size();
        int window = cfg.getWindowSize();
        int earlyCount = total - window;
        String summary = cachedSummary;
        boolean changed = false;

        if (cfg.getMode() == VoiceInterviewProperties.Mode.SUMMARY
                && earlyCount > coveredTurns
                && earlyCount - coveredTurns >= cfg.getSummaryBatchSize()) {
            // 仅对「尚未覆盖的早期轮次」做增量摘要合并，避免每轮都调用 LLM
            // earlyCount > coveredTurns 防御上游脏数据（如被损坏的 SUMMARY 行），避免 subList(from > to) 抛异常
            List<String> earlyTurns = formatRecent(turns.subList(coveredTurns, earlyCount));
            String newSummary = summarize(cachedSummary, earlyTurns);
            if (newSummary != null && !newSummary.equals(cachedSummary)) {
                summary = newSummary;
                coveredTurns = earlyCount;
                changed = true;
            } else {
                // 摘要未变化（或生成失败降级）：保持现状，不标记 changed，避免无谓持久化
                summary = newSummary != null ? newSummary : cachedSummary;
            }
        }

        List<VoiceInterviewMessageEntity> recent = turns.subList(earlyCount, total);
        return new CompressedHistory(summary, recent, coveredTurns, changed);
    }

    /**
     * 将实体轮次格式化为「面试官：/候选人：」文本行，与原 getHistory 的格式化逻辑保持一致。
     */
    public List<String> formatRecent(List<VoiceInterviewMessageEntity> turns) {
        List<String> history = new ArrayList<>();
        String pendingAiQuestion = null;
        for (VoiceInterviewMessageEntity msg : turns) {
            String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
            String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());
            if (pendingAiQuestion != null) {
                history.add("面试官：" + pendingAiQuestion);
                pendingAiQuestion = null;
                if (userText != null) {
                    history.add("候选人：" + userText);
                }
                if (aiText != null) {
                    pendingAiQuestion = aiText;
                }
                continue;
            }
            if (aiText != null && userText != null) {
                history.add("面试官：" + aiText);
                history.add("候选人：" + userText);
            } else if (aiText != null) {
                pendingAiQuestion = aiText;
            } else if (userText != null) {
                history.add("候选人：" + userText);
            }
        }
        if (pendingAiQuestion != null) {
            history.add("面试官：" + pendingAiQuestion);
        }
        return history;
    }

    /**
     * 将早期轮次增量合并进已有摘要。摘要生成失败则降级沿用已有摘要，不阻塞主链路。
     */
    private String summarize(String prevSummary, List<String> earlyTurns) {
        if (earlyTurns == null || earlyTurns.isEmpty()) {
            return prevSummary;
        }
        try {
            String prompt = buildSummaryPrompt(prevSummary, earlyTurns);
            // 使用不带 SkillsTool / MemoryAdvisor 的 plain client：摘要是纯文本压缩，
            // 不应混入面试素材工具，也不应让 MemoryAdvisor 重新注入完整历史（否则抵消压缩收益）
            String result = llmProviderRegistry.getPlainChatClient()
                    .prompt().user(prompt).call().content();
            return (result == null || result.isBlank()) ? prevSummary : result.trim();
        } catch (Exception e) {
            log.warn("上下文摘要生成失败，降级沿用已有摘要", e);
            return prevSummary;
        }
    }

    private String buildSummaryPrompt(String prevSummary, List<String> earlyTurns) {
        return new StringBuilder()
                .append("你是一个面试对话摘要器。下方是一段语音面试的「前情摘要」（可能为空）与「新增对话轮次」。\n")
                .append("请将新增轮次合并进前情摘要，输出一段简洁、保留关键事实（候选人提到的项目/技术栈/经历/已答题点）的中文摘要，")
                .append("不要编造未提及的内容。\n\n")
                .append("【前情摘要】\n").append(prevSummary == null ? "(空)" : prevSummary).append("\n\n")
                .append("【新增轮次】\n").append(String.join("\n", earlyTurns)).append("\n\n")
                .append("【合并后摘要】")
                .toString();
    }

    /**
     * 压缩结果。
     *
     * @param summary     早期轮次的滚动摘要（mode=SUMMARY 且已触发时非 null）
     * @param recent      保留的近期轮次实体（窗口内）
     * @param coveredTurns 已被摘要覆盖的轮次数
     * @param changed     摘要是否较缓存发生变化（需持久化）
     */
    public record CompressedHistory(String summary,
                                     List<VoiceInterviewMessageEntity> recent,
                                     int coveredTurns,
                                     boolean changed) {
    }
}
