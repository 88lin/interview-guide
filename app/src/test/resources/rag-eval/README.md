# RAG 测评集（P1-01）

最小可复现的 RAG 基线测评：固定数据集 + 固定配置 + 独立可销毁环境，一条命令产出 JSON/Markdown 报告。

## 运行方式

前置条件：本机 Docker（PostgreSQL + pgvector、Redis）、`.env` 中已配置可用的 Embedding/Chat Provider。

```bash
# 基线（Chunk 800 + 关闭改写，rag-eval Profile 固定）
RUN_RAG_EVAL=true ./gradlew :app:ragEvaluation --no-daemon

# 对照（开启改写，独立 run）
RUN_RAG_EVAL=true APP_AI_RAG_REWRITE_ENABLED=true ./gradlew :app:ragEvaluation --no-daemon
```

- `RUN_RAG_EVAL`：双保险开关之一（另一层是 `rag-eval` 标签，普通 `:app:test` 不会运行本测评）。
- 命令行显式设置的环境变量优先于 `.env`，便于独立切换改写、融合和分块配置。
- Redis 隔离：`rag-eval` Profile 默认使用 database 1（可用 `REDIS_DATABASE` 覆盖），显式设为 0 会被测评启动守卫拒绝；task 会自动加载根目录 `.env`（含 `APP_AI_CONFIG_ENCRYPTION_KEY` 等）。
- 普通测试命令 `./gradlew :app:test` 通过 `excludeTags 'rag-eval'` 排除本测评，不产生付费调用。

## 环境隔离

- 每次运行使用独立的 PostgreSQL 逻辑库 `interview_guide_rag_eval`：启动前 drop & create（含 Flyway 迁移），结束后 drop，不触碰开发库。
- 测评知识库为库内两条临时 `knowledge_bases` 记录，随库销毁。
- Redis 使用独立 database index，Stream 消息与开发环境互不可见。

## 数据 Schema（dataset-v1.jsonl，40 条）

| 字段 | 说明 |
|---|---|
| `id` | 稳定唯一；前缀对应题型：fact-/para-/ctx-/chunk-/oos- |
| `question` | 用户问题 |
| `history` | 可选多轮上下文 `{role, content}`（仅 ctx- 类使用） |
| `fixture` | 固定知识库文件名（fixtures/ 下），不写运行时 ID |
| `expectedEvidence` | 证据锚点 `[{id, text}]`；片段含任一锚点即命中，多锚点用于覆盖率 |
| `shouldReject` | 知识库外问题标记（oos- 类全部为 true） |
| `tags` | 题型分组统计键 |
| `split` | `dev`（30，调参用）/ `holdout`（10，仅终评） |
| `evaluateGeneration` | 是否执行端到端生成（固定 10 条 dev 站内样本 + 全部 8 条 oos） |

题型分布：12 直接事实 / 8 同义改写 / 6 多轮上下文 / 6 跨段 / 8 知识库外；dev 30 条 / holdout 10 条。默认生产分块配置下，每篇 fixture 的 Chunk 数大于最大 Top K（20），跨段样本锚点经 `RagEvalCorpusTest` 校验确实落在不同 Chunk。块数约束用于避免候选集过小；Kafka、RabbitMQ 等同领域干扰内容另有断言，用于误召回与拒答场景。

所有内容人工编写，无真实手机号、邮箱、姓名与公司信息。

## 指标定义

- **Hit@K**：前 K 个片段中至少出现一个期望证据锚点的样本占比。
- **EvidenceRecall@K**：命中锚点数 / 该样本全部锚点数（按证据锚点口径，非标准 IR 的 Document Recall；锚点匹配前做大小写、空白与 Markdown 归一化）。
- **MRR**：每题取所有证据锚点的最小命中排名，再求倒数均值；未命中计 0，不受锚点标注顺序影响。
- **拒答 P/R/F1**：生成子集以 `shouldReject` 为实际标签、`NO_RESULT` 为预测拒答，统计知识库内误拒答和知识库外未拒答。
- **忠实度**：固定 10 条生成样本人工复核，报告字段 `faithfulnessStatus: PASS|FAIL|UNSURE|PENDING`。
- **耗时**：P50/P95 总耗时、改写、检索、生成分阶段耗时（来自 `RagQueryExecution` 轨迹）。
- **Token**：当前链路使用 `.content()` 无法取得 usage，报告记 null 并标注；补齐属于 P1-04 范围。
- **坏例**：MRR 排名 > 3、EvidenceRecall < 1、oos 未拒答的样本逐条列出。

统计范围：检索质量指标只使用 32 条知识库内样本，改写与检索耗时使用全部有效样本，生成与端到端耗时及拒答矩阵只使用 18 条生成样本。`HARNESS_ERROR` 被排除并单独计数。各阶段分位数不能直接相加。

新报告同时输出 `validSamples`、各阶段的 `*Samples` 和拒答矩阵的 `samples`。`rewriteChangedSamples` 按逐样本 `rewrittenQuestion != question` 计数；`multiQuerySamples` 统计 `attemptedQueries` 多于一条的样本，可能来自双路融合或单路回退，不能直接等同于融合次数。

## 报告

- 原始输出：`app/build/reports/rag-eval/<run-id>.json` 与同名 `.md`（build 目录不入 Git）。
- 正式基线：人工复核并确认脱敏后，复制到本目录 `baselines/<run-id>.json|.md` 入 Git，与 dataset/fixture/Prompt hash、Git SHA、模型标识绑定。
- 配置快照读取 Spring 实际绑定的值，包含完整 vectorization、search 和 history 参数，不仅记录环境变量中的 chunkSize。
- `evaluatorVersion=evidence-rank-v2` 表示已修复多证据首命中排名。此前实现取标注顺序中第一个命中证据的排名，可能低估多证据题 MRR，并误标 `LOW_FIRST_RANK`。历史归档保持原样，其 MRR 不能作为修正后的结果；确认模型费用与环境后重新评测，生成新归档。旧报告仅保留片段前 200 字，不能假定可以从旧 JSON 完整重算。

## 复现注意

- P2-01 来源追踪变更后，Prompt 包含 `[S1]` 等编号，成功答案由服务端附加实际送入 Prompt 的片段尾注。历史基线仍保留，不与新 Prompt 的生成质量/耗时直接比较；编号表示“提供给模型”，不保证模型确实使用或正确引用了每个片段。
- 新上传或手动重新向量化才会补齐来源名称、文件名与稳定 `source_id`；旧向量兼容降级为“知识库片段”，不编造缺失信息。此变更不会自动批量重新向量化开发库。
- 来源只在当前检索知识库范围内展示；项目尚无完整用户级权限隔离，只适合可信私有部署。不得把来源尾注能力当作多租户授权能力。
- 两次 run 可比的前提：同 dataset 版本、同 fixture hash、同 Prompt hash、同 Git SHA、同模型与配置（run 头部完整记录）。
- 新增盲测样本写入 `dataset-v2.jsonl`，不修改 v1。
- 前置环境不可用时任务 FAIL 并产出 `HARNESS_ERROR` 报告，不会把“没跑”误报为“通过”。
