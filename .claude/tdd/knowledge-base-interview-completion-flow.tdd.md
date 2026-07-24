# 知识库面试完成流程 TDD 证据

## 来源

本轮没有外部计划文件。用户旅程来自 2026-07-24 的本地浏览器完整流程测试：

- 用户完成最后一道题后，应看到评估等待状态，并在评估完成后自动进入本次详情。
- 用户进入题库管理页时，历史生成任务不能自动套用草稿筛选并造成“暂无题目”假象。
- 评估中的记录不能提前计入“已完成”。
- 未限定单一方向的知识库面试应显示“全部方向”，而不是“未指定方向”。

## RED 证据

命令：

```bash
cd frontend
node --test src/pages/questionGenerationStatus.test.ts \
  src/pages/interviewHistoryStats.test.ts \
  src/pages/knowledgeBaseInterviewCompletion.test.ts
```

结果：`2 pass / 3 fail`，退出码 `1`。

- `null -> COMPLETED` 错误返回 `true`，复现历史生成状态触发空筛选。
- `getKnowledgeBaseInterviewCategoryLabel` 尚不存在，方向展示测试编译失败。
- `knowledgeBaseInterviewCompletion.ts` 尚不存在，完成流程测试编译失败。

RED 检查点：`20ca85c test: 复现知识库面试完成流程问题`

## GREEN 证据

针对性命令：

```bash
cd frontend
node --test src/pages/questionGenerationStatus.test.ts \
  src/pages/interviewHistoryStats.test.ts \
  src/pages/knowledgeBaseInterviewCompletion.test.ts
```

结果：`9 pass / 0 fail`。

完整前端单元测试：

```bash
cd frontend
node --test src/pages/*.test.ts src/components/knowledgebaseInterview/*.test.ts
```

结果：`13 pass / 0 fail`。

生产构建：

```bash
cd frontend
pnpm run build
```

结果：TypeScript 编译和 Vite 构建成功。构建仍有项目原有的 Tailwind `:where()` CSS 警告和大 chunk 警告，没有新增构建失败。

GREEN 检查点：`1f82388 fix: 完善知识库面试完成后的结果流程`

## 浏览器回归

在 `JavaGuide面试突击版5.0` 上创建了会话 `69670ae4027a413d`：

1. 配置 1 道主问题、0 个追问并完成作答。
2. 交卷后停留在“正在生成面试评估”页面，没有跳到题库管理。
3. 评估期间记录页显示“面试总数 3、已完成 2”，新记录方向为“全部方向”。
4. 评估完成后等待页自动跳转至
   `/knowledgebase-interview/1/interviews/69670ae4027a413d`。
5. 详情页正常显示 73 分及逐题评价。
6. 记录页自动更新为“面试总数 3、已完成 3”。
7. 直接进入题库管理页时 5 道题正常显示，不再出现空列表。
8. 主流程页和记录页浏览器控制台错误均为 0。

## 测试规格

| # | 保证内容 | 测试或验证 | 类型 | 结果 |
|---|---|---|---|---|
| 1 | 只有活动生成任务进入 `COMPLETED` 才刷新题库筛选 | `questionGenerationStatus.test.ts` | 单元 | PASS |
| 2 | 页面首次读取历史 `COMPLETED` 不会切换到空草稿列表 | 单元测试 + 浏览器直接进入题库 | 单元 / E2E | PASS |
| 3 | 评估中的知识库面试不计入已完成数量 | `interviewHistoryStats.test.ts` + 浏览器评估中记录 | 单元 / E2E | PASS |
| 4 | 空方向元数据展示为“全部方向” | `interviewHistoryStats.test.ts` + 记录列表 | 单元 / E2E | PASS |
| 5 | `PENDING` / `PROCESSING` 保持等待状态 | `knowledgeBaseInterviewCompletion.test.ts` | 单元 | PASS |
| 6 | `COMPLETED` 进入当前知识库的本次面试详情 | 单元测试 + 浏览器完整流程 | 单元 / E2E | PASS |
| 7 | `FAILED` 有明确失败状态，不误跳详情 | `knowledgeBaseInterviewCompletion.test.ts` | 单元 | PASS |

## 覆盖率与已知缺口

当前前端没有覆盖率脚本，也没有配置可用的 Node TypeScript 覆盖率收集流程，因此本轮无法给出可信的百分比覆盖率。已通过 13 个现有及新增单元测试、TypeScript 生产构建和真实浏览器端到端流程覆盖本次改动。评估失败页面只做了单元测试，没有人为制造真实 LLM 失败。
