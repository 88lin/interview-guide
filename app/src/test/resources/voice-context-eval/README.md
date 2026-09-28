# 语音上下文压缩对照（P0-05）

```bash
RUN_VOICE_CONTEXT_EVAL=true ./gradlew :app:voiceContextEvaluation --no-daemon
```

需要 `.env` 中的 `AI_BAILIAN_API_KEY`。可通过环境变量 `AI_MODEL` 覆盖默认 `qwen3.5-flash`，环境变量优先于 `.env`。正常路径为 1 次摘要 + 6 次回答调用，会产生 Provider 费用；普通 `:app:test` 排除 `voice-context-eval` 标签，独立任务仍要求显式环境变量开关。

## 测量口径

- 测试类 `VoiceContextEvaluationTest` 固定生成 60 轮完全虚构、重复度较高的技术问答，无业务数据库、用户录音、转录、个人信息。
- 直接调用生产 `VoiceContextCompressor`，配置为 SUMMARY / windowSize=20 / summaryBatchSize=10 / maxHistoryChars=12000 / maxSummaryChars=4000，真实生成摘要。摘要失败即测试失败，不把 WINDOW 降级记成摘要成功。
- before 为关闭压缩时的全量格式化历史；after 为摘要与保留窗口，使用 `VoiceHistoryLoader` 相同的摘要标签与换行拼接形式。报告字符数包括这些标签和分隔符，不等同于压缩器内部预算计数，更不等于 Token 数。
- 三组 before/after 交替顺序调用同一个模型、温度 0.2 和固定追问提示词。耗时为非流式模型调用墙钟耗时（包括网络、SDK 开销及可能的重试），不是 TTFT，也不包括 ASR/TTS。
- Token 来自 Provider 响应 usage，缺失记 null；报告保留响应模型标识，不保证供应商别名固定到某个权重版本。未显式设置的模型选项沿用 Provider 默认。
- 摘要耗时与 Token 单独记录。触发摘要的那轮需把摘要成本加到压缩后生成成本上；后续缓存复用不重复支付这次摘要成本。

这是压缩器层面的冷启动长历史对照，**不是生产 `VoiceHistoryLoader` 的有界读取/增量追赶重放**：生产每次仅读取有限批次，因此不能把本测试一次摘要 40 轮的成本当作生产每轮成本。

## 产物与限制

原始报告写入 `app/build/reports/voice-context-eval/`；确认脱敏后归档到本目录 `baselines/`。JSON 包含数据哈希、配置、模型、历史字符数和逐次耗时/Token；Markdown 为阅读版。失败运行不会产出成功报告，应检查 Gradle 测试结果。

仅三组且使用高重复虚构语料，网络、缓存和模型推理输出长度均会影响结果。这些数据只能证明本次执行与成本口径，不能证明答案质量不下降、生产延迟必然降低或长期成本下降。需要正式性能结论时另做代表性会话、质量复核和生产观测。

## 2026-09-19 测量中发现的模板缺陷

首次真实运行 `voice-context-1789801015819` **作废，不作为基线**。其摘要调用只有 106 个输入 Token，检查发现生产模板使用 `<previousSummary>` / `<newTurns>`，而 `PromptTemplate` 默认使用 `{...}`，实际历史没有被注入。

模板已修正为 `{previousSummary}` / `{newTurns}`，并新增使用真实模板的单元测试与付费测评发送前断言：摘要 Prompt 必须包含实际轮次，不得保留占位符。有效重跑额外记录每次请求字符数、哈希和摘要模板哈希。

不会自动删除或重建已有持久化 SUMMARY。它们可能由旧模板生成；验证修复请使用新会话。若需要修复历史摘要，应先确定受影响会话和备份方案，再执行单独的数据修复，不能在本次测评中隐式清库。
