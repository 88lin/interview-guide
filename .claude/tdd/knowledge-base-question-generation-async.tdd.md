# 知识库问题异步生成 TDD 证据

## Source plan

本轮直接依据用户提供的“知识库问题异步生成优化方案”拆分用户旅程和测试目标，
未使用独立的 `*.plan.md` 文件。

## User journeys

1. 用户提交生成请求后立即进入题库管理页，页面持续展示服务端任务状态。
2. 同一知识库同时只能存在一个活动任务，重复消息和旧 Worker 不能覆盖当前任务。
3. 消费失败后任务能够重新排队；投递失败或重试耗尽后进入安全的失败状态。
4. Worker 或服务异常退出后，长时间停留在 `QUEUED` / `PROCESSING` 的任务能够恢复。
5. 新题替换和 `COMPLETED` 状态在同一个事务内提交，任一步失败都会整体回滚。

## Task report

### RED：状态机与旧 Worker 复现

- 执行：`./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.service.QuestionGenerationAsyncTest' --no-daemon`
- 结果：13 个测试中 2 个按预期失败。
- 失败证据：首次消费失败后状态仍为 `PROCESSING`；任务 ID 变化后旧 Worker 仍保存了结果。
- 检查点：`7915d65 test: 添加题目异步生成可靠性复现用例`

### RED：独立状态事务契约

- 执行：编译 `QuestionGenerationStateServiceTest`。
- 结果：按预期因状态服务、配置快照和实体字段尚不存在而编译失败。
- 检查点：`7cf90b3 test: 定义题目生成状态事务契约`

### GREEN：后端可靠性

- 执行：`./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.service.KnowledgeBaseQuestionServiceTest' --tests 'interview.guide.modules.knowledgebase.service.QuestionGeneration*Test' --no-daemon`
- 结果：`BUILD SUCCESSFUL`。
- 保证：任务创建和投递分离、原子领取、重试复位、过期任务恢复、旧结果丢弃及题目替换完成事务均满足测试契约。
- 检查点：`3ac6178 fix: 完善知识库题目异步生成可靠性`

### GREEN：前端异步状态

- RED：`node --test src/pages/questionGenerationStatus.test.ts` 因状态辅助模块不存在而失败。
- GREEN：`pnpm run test:question-generation`，3 个测试全部通过。
- 保证：活动状态识别、仅首次完成时刷新以及失败信息脱敏行为稳定。
- 检查点：`4f5765f feat: 支持题目生成异步状态轮询`

### Refactor：收敛生成逻辑

- 删除旧同步生成入口和 `KnowledgeBaseQuestionGenerationResult`。
- 将批内去重、分类兜底、已有分类和已有题目 Prompt 覆盖迁移到异步生成组件测试。
- 执行：后端定向测试和全量 `./gradlew :app:test --no-daemon`。
- 结果：`BUILD SUCCESSFUL`，全量测试耗时 1m49s。

## Test specification

| # | What is guaranteed | Test file or command | Test type | Result | Evidence |
|---|--------------------|----------------------|-----------|--------|----------|
| 1 | 创建任务提交后才投递，投递失败写回 `FAILED` | `QuestionGenerationAsyncTest` | unit | PASS | 后端定向测试 |
| 2 | 活动任务拒绝重复提交，重复消息只有一个消费者能领取 | `QuestionGenerationStateServiceTest` / `QuestionGenerationAsyncTest` | unit | PASS | 后端定向测试 |
| 3 | 首次失败恢复 `QUEUED`，重试耗尽进入 `FAILED` | `QuestionGenerationAsyncTest` | unit | PASS | 后端定向测试 |
| 4 | Pending reclaim 与过期 `PROCESSING` 任务可恢复 | `QuestionGenerationAsyncTest` | unit | PASS | 后端定向测试 |
| 5 | taskId 变化后旧 Worker 不删除或保存题目 | `QuestionGenerationAsyncTest` | unit | PASS | 后端定向测试 |
| 6 | 题目替换和完成状态使用同一事务边界 | `QuestionGenerationStateServiceTest` | unit | PASS | 后端定向测试 |
| 7 | 前端活动状态、完成刷新和安全失败提示符合约定 | `pnpm run test:question-generation` | unit | PASS | 3 tests passed |
| 8 | 前端 TypeScript 与生产构建成功 | `pnpm run build` | build | PASS | Vite build completed |
| 9 | PostgreSQL 迁移无重复版本且可执行至 `20260724` | 临时 PostgreSQL 16 数据库启动 Flyway | integration | PASS | Successfully applied 4 migrations |
| 10 | 提交后立即跳转并经轮询展示完成数量和新题 | 浏览器 + 本地 Mock API | browser | PASS | 页面展示“已生成 1 道题，跳过 1 道重复题” |

## Coverage and known gaps

- 项目未配置本次功能可独立执行的覆盖率门禁，因此未声明覆盖率百分比。
- 浏览器测试使用本地 Mock API 验证主成功链路；失败与卸载清理由状态辅助函数测试和 React effect 清理代码覆盖，尚未引入新的浏览器测试框架依赖。
- 前端构建存在既有的 CSS `:where()` 压缩警告和大 chunk 警告，但构建成功，且本次改动未新增对应样式规则。

## Merge evidence

- RED 检查点：`7915d65`、`7cf90b3`
- GREEN 检查点：`3ac6178`、`4f5765f`
- 最终回归：后端全量测试、前端定向测试、前端生产构建、PostgreSQL Flyway 和浏览器 Mock 主流程全部通过。
