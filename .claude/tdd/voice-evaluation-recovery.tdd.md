# 语音面试评估恢复与状态体验 TDD 证据

## 来源与用户旅程

本次改动来自用户反馈：语音面试结束后，评估结果长时间停留在“等待评估 / 生成中”。

1. 作为面试用户，我希望异常断线结束会话后仍能自动生成评估。
2. 作为面试用户，我希望排队、分析、延迟和失败状态有明确区别，并能放心离开页面。
3. 作为面试用户，我希望卡住或失败的任务可以真实重新生成，而不是继续返回原状态。
4. 作为面试用户，我希望没有有效问答时显示“暂无评分”，而不是误导性的 0 分。

## RED / GREEN 记录

| 阶段 | 命令 | 结果 | 说明 |
| --- | --- | --- | --- |
| RED | `./gradlew :app:test --tests 'interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationRecoveryTest' --tests 'interview.guide.modules.voiceinterview.controller.VoiceInterviewControllerEvaluationTest' --no-daemon` | 失败 | 5 个用例全部失败：异常断线和超时会话未投递、PENDING 未恢复、POST 未重试、接口缺少状态时间。 |
| RED | `pnpm exec node --test src/pages/voiceEvaluationStatus.test.ts` | 失败 | 状态展示策略模块尚不存在。 |
| RED | `VoiceInterviewEvaluationRecoveryTest.shouldInvalidateSessionCacheAfterEvaluationStatusChanges` | 失败 | 评估状态变化后旧会话缓存未失效。 |
| RED | `VoiceInterviewEvaluationServiceTest` | 失败 | 无有效对话时评估仍写入 0 分。 |
| RED | `shouldUseThreeMinutePendingRecoveryThreshold` 与前端活动状态刷新测试 | 失败 | 后端恢复与前端提示使用同一时间边界，且活动任务未声明需要刷新时间展示。 |
| GREEN | 上述后端定向测试 | 通过 | 可靠投递、自动恢复、显式重试、状态时间和缓存一致性全部转绿。 |
| GREEN | `pnpm exec node --test src/pages/voiceEvaluationStatus.test.ts` | 通过 | 4 个排队、延迟、处理、失败和活动状态刷新用例通过。 |
| 回归 | `./gradlew :app:test --no-daemon` | 通过 | 完整后端测试套件通过。 |
| 回归 | `pnpm exec node --test ...` | 通过 | 前端现有 17 个 Node 测试全部通过。 |
| 构建 | `pnpm run build` | 通过 | TypeScript 检查和 Vite 生产构建通过。 |

## 测试保证

| # | 保证 | 测试或验证 | 类型 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | WebSocket 异常断开自动结束会话后会投递评估任务。 | `shouldEnqueueEvaluationAfterDisconnectEndsSession` | 单元测试 | 通过 |
| 2 | 定时结束超时会话后会投递评估任务。 | `shouldEnqueueEvaluationAfterCleaningStaleInterview` | 单元测试 | 通过 |
| 3 | 长时间 PENDING 的评估会自动重新投递。 | `shouldRequeueStalePendingEvaluation` | 单元测试 | 通过 |
| 4 | 评估状态变化后会清除会话缓存。 | `shouldInvalidateSessionCacheAfterEvaluationStatusChanges` | 单元测试 | 通过 |
| 5 | 用户对 PENDING 任务显式重试时会真实投递。 | `shouldRequeuePendingEvaluationOnExplicitRetry` | 控制器测试 | 通过 |
| 6 | 评估状态接口返回状态更新时间。 | `shouldExposeEvaluationStatusUpdatedAt` | 控制器测试 | 通过 |
| 7 | 没有有效对话时不写入 0 分。 | `shouldNotAssignZeroScoreWhenNoConversationExists` | 单元测试 | 通过 |
| 8 | 新 PENDING、延迟 PENDING、PROCESSING、FAILED 使用不同提示和操作。 | `voiceEvaluationStatus.test.ts` | 前端逻辑测试 | 通过 |
| 9 | 前端在 2 分钟提示延迟，后端在 3 分钟自动恢复，活动任务轮询会刷新时间展示。 | `shouldUseThreeMinutePendingRecoveryThreshold` 与 `shouldRefreshVoiceEvaluationPresentation` | 边界测试 | 通过 |
| 10 | 历史列表显示“评估延迟 / 可重新生成”，详情页提供重新生成入口。 | 本地浏览器 + 临时测试会话 | 浏览器验收 | 通过 |
| 11 | 点击重新生成后任务进入 Redis Stream，消费者完成处理，页面自动展示报告。 | 本地 Spring Boot、Redis Stream、PostgreSQL、浏览器 | 端到端验收 | 通过 |
| 12 | 无有效对话的报告总分显示为“—”。 | 浏览器最终页面截图与 DOM | 视觉验收 | 通过 |

## 覆盖率与已知说明

`app/build.gradle` 未配置 JaCoCo 或其他覆盖率任务，因此无法提供数值覆盖率。新增测试直接覆盖本次修改的关键状态分支和恢复路径。

浏览器验收使用 ID 3、4、5 的临时本地会话；验收结束后已通过业务删除接口精确清理，并确认数据库中相关记录数量为 0。测试时发现 8080 已有开发进程，因此当前代码临时运行在 8081，未停止或修改原有进程。
