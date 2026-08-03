# 语音面试 TTS 握手失败修复证据

## 来源与用户旅程

本次测试由语音面试现场日志和卡住截图驱动，没有外部计划文件。

作为语音面试用户，我希望云端 TTS WebSocket 握手失败时当前回答能及时结束，避免页面永久停留在“面试官正在回答…”。

## 任务报告

### RED：复现 SDK 握手失败后阻塞

- 测试：`QwenTtsServiceTest.shouldReturnPromptlyWhenWebSocketHandshakeFails`
- 命令：`./gradlew :app:test --tests 'interview.guide.modules.voiceinterview.service.QwenTtsServiceTest.shouldReturnPromptlyWhenWebSocketHandshakeFails' --no-daemon`
- 结果：失败；2 秒预抢占超时触发 `ExecutionTimeoutException`。
- 证明：DashScope SDK 2.22.7 的失败回调只记录错误，没有解除 `connect()` 的等待。

### GREEN：失败回调与建连超时保护

- 同一回归测试修复后通过。
- `QwenTtsServiceTest` 与 `VoiceInterviewWebSocketHandlerTest` 定向测试通过。
- 使用当前 `.env` 凭据执行一次真实 DashScope 短文本合成，成功返回非空音频。
- 完整命令 `./gradlew :app:test --no-daemon` 通过，耗时 1 分 49 秒。

## 测试规格

| # | 保证 | 测试或命令 | 类型 | 结果 |
|---|---|---|---|---|
| 1 | WebSocket 握手失败时 TTS 调用不会永久阻塞 | `QwenTtsServiceTest.shouldReturnPromptlyWhenWebSocketHandshakeFails` | 单元/网络失败回归 | PASS |
| 2 | TTS 与语音 WebSocket 既有行为未回归 | `QwenTtsServiceTest`、`VoiceInterviewWebSocketHandlerTest` | 单元 | PASS |
| 3 | 当前 DashScope 配置仍可完成真实语音合成 | 临时 `QwenTtsLiveSmokeTest`，验证后已删除 | 在线烟测 | PASS |
| 4 | 后端测试套件保持通过 | `./gradlew :app:test --no-daemon` | 回归 | PASS |

## 覆盖率与已知缺口

项目当前未配置 JaCoCo 或其他后端覆盖率任务，因此无法给出数值覆盖率。回归测试直接覆盖本次故障的阻塞分支；真实云端握手仍可能受网络和供应商瞬时状态影响。

## 合并证据

- RED 提交：`b1b8133 test: 复现 TTS 握手失败后阻塞`
- GREEN 提交：由本次修复提交记录。
