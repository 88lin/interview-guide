# 知识库面试严格追问容量 TDD 证据

## 来源与用户旅程

需求来自本次对话确认，不使用外部计划文件。

- 作为面试发起者，我选择每题 N 个追问时，希望每道入选题都严格提供 N 个追问。
- 作为面试发起者，我希望在开始前看到各追问数量对应的可用主问题数。
- 作为题库维护者，我希望看到题目实际追问数与最近一次生成目标的差距。

## 任务报告

| 行为 | RED 证据 | GREEN 证据 | 保证 |
| --- | --- | --- | --- |
| 严格追问数量与容量矩阵 | `./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.service.KnowledgeBaseInterviewServiceTest' --tests 'interview.guide.modules.knowledgebase.service.QuestionGenerationAsyncTest' --no-daemon` 因容量响应、容量方法和专用错误码不存在而编译失败 | 同一命令 `BUILD SUCCESSFUL` | 追问不足时拒绝创建；非空题干才计数；容量按方向和难度统计 |
| 生成追问目标归一化 | 上述后端 RED 测试引用尚未实现的目标归一化行为 | `QuestionGenerationAsyncTest` 通过 | 超出目标的追问被截断；不足目标的有效草稿仍保存 |
| 前端容量与质量提示 | `pnpm run test:interview-capacity` 因 `interviewCapacity.ts` 不存在而失败 | 同一命令 4 项测试全部通过 | 保留严格数量选择、显示最大可保证数量、主问题不足时给出准确提示 |
| 全量回归 | 不适用 | `./gradlew :app:test --no-daemon`，`BUILD SUCCESSFUL in 1m 47s` | 后端现有测试未回归 |
| 前端回归 | 不适用 | 三组 Node 测试全部通过；`pnpm run build` 成功 | TypeScript、Vite 生产构建及现有纯函数测试通过 |

## 测试规格

| # | 可验证保证 | 测试位置 | 类型 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | 追问池少于请求数量时返回 `INTERVIEW_QUESTION_INSUFFICIENT` | `KnowledgeBaseInterviewServiceTest` | 单元 | PASS |
| 2 | 空白追问题干不计入严格容量 | `KnowledgeBaseInterviewServiceTest` | 单元 | PASS |
| 3 | 容量响应同时返回方向统计和 0～5 个追问的可用题数 | `KnowledgeBaseInterviewServiceTest` | 单元 | PASS |
| 4 | 追问池大于请求数量时随机抽取恰好 N 个且不重复 | `KnowledgeBaseInterviewServiceTest` | 单元 | PASS |
| 5 | 生成追问超过目标时截断，少于目标时保留草稿 | `QuestionGenerationAsyncTest` | 单元 | PASS |
| 6 | 前端严格容量提示不会静默降低用户选择 | `interviewCapacity.test.ts` | 单元 | PASS |
| 7 | 最近生成目标只标记追问不足题目 | `interviewCapacity.test.ts` | 单元 | PASS |

## 覆盖率与已知缺口

- 项目当前未配置 JaCoCo 或前端覆盖率脚本，因此无法输出可信的百分比覆盖率。
- 已运行知识库面试定向测试、完整后端测试、全部现有前端 Node 测试和前端生产构建。
- 未运行真实浏览器联调；该流程依赖本地后端、数据库及已有知识库数据。

## 合并证据

- RED 检查点：`54e1bb0 test: 增加知识库面试严格容量测试`
- GREEN 检查点：`9cdcb8c fix: 严格校验知识库面试追问数量`
