package interview.guide.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 测评指标计算单元测试（不调用模型）。
 */
@DisplayName("RAG 测评指标计算")
class RagEvalMetricsTest {

  private Map<String, Object> sample(String outcome, boolean shouldReject, boolean evaluateGeneration,
                                     Boolean hit, Integer firstHitRank, double evidenceRecall,
                                     boolean predictedReject) {
    Map<String, Object> m = new java.util.HashMap<>();
    m.put("outcome", outcome);
    m.put("shouldReject", shouldReject);
    m.put("evaluateGeneration", evaluateGeneration);
    m.put("hit", hit);
    m.put("firstHitRank", firstHitRank);
    m.put("evidenceRecall", evidenceRecall);
    m.put("predictedReject", predictedReject);
    m.put("retrievalMs", 100L);
    m.put("rewriteMs", 50L);
    m.put("generationMs", 5000L);
    m.put("totalMs", 5200L);
    return m;
  }

  @Test
  @DisplayName("MRR 中未命中样本贡献 0，而不是被剔除")
  void mrrCountsMissAsZero() {
    // 3 个站内样本：排名 1、未命中、排名 5 → MRR = (1 + 0 + 0.2) / 3
    List<Map<String, Object>> results = List.of(
        sample("RETRIEVED", false, false, true, 1, 1.0, false),
        sample("RETRIEVED", false, false, false, null, 0.0, false),
        sample("RETRIEVED", false, false, true, 5, 1.0, false));

    Map<String, Object> metrics = RagEvalMetrics.metricsOf(results);

    assertThat(metrics.get("MRR")).isEqualTo(0.4);
    assertThat(metrics.get("Hit@K(%)")).isEqualTo(66.6667);
  }

  @Test
  @DisplayName("拒答混淆矩阵统计全部生成样本，含误拒答")
  void rejectionMatrixCoversAllGenerationSamples() {
    List<Map<String, Object>> results = List.of(
        sample("NO_RESULT", true, true, false, null, 1.0, true),     // tp
        sample("ANSWERED", true, true, false, null, 1.0, false),    // fn
        sample("NO_RESULT", false, true, true, 1, 1.0, true),       // fp（站内误拒答）
        sample("ANSWERED", false, true, true, 1, 1.0, false),       // tn
        sample("RETRIEVED", false, false, true, 1, 1.0, false));    // 非生成样本不计入

    Map<String, Object> rejection = RagEvalMetrics.rejectionMetrics(results);

    assertThat(rejection.get("tp")).isEqualTo(1);
    assertThat(rejection.get("fn")).isEqualTo(1);
    assertThat(rejection.get("fp")).isEqualTo(1);
    assertThat(rejection.get("tn")).isEqualTo(1);
    assertThat((Double) rejection.get("accuracy")).isEqualTo(0.5);
    assertThat((Double) rejection.get("f1")).isEqualTo(0.5);
  }

  @Test
  @DisplayName("HARNESS_ERROR 样本被排除并单独计数")
  void harnessErrorsExcluded() {
    List<Map<String, Object>> results = List.of(
        sample("RETRIEVED", false, false, true, 1, 1.0, false),
        sample("HARNESS_ERROR", false, true, null, null, 0.0, false));

    Map<String, Object> metrics = RagEvalMetrics.metricsOf(results);

    assertThat(metrics.get("harnessErrorCount")).isEqualTo(1);
    assertThat(metrics.get("samples")).isEqualTo(2);
    assertThat(metrics.get("MRR")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("分阶段耗时按生成子集与检索子集分别输出")
  void stagePercentilesSplit() {
    Map<String, Object> retrieval = sample("RETRIEVED", false, false, true, 1, 1.0, false);
    retrieval.put("generationMs", 90000L);
    retrieval.put("totalMs", 100000L);
    List<Map<String, Object>> results = List.of(
        retrieval,
        sample("ANSWERED", false, true, true, 1, 1.0, false));

    Map<String, Object> metrics = RagEvalMetrics.metricsOf(results);

    // 检索耗时在两个样本上都输出；生成与端到端只在生成子集上输出
    assertThat(metrics).containsKeys("retrievalMsP50", "retrievalMsP95", "rewriteMsP50");
    assertThat(metrics).containsEntry("retrievalSamples", 2)
        .containsEntry("rewriteSamples", 2)
        .containsEntry("generationSamples", 1)
        .containsEntry("endToEndSamples", 1)
        .containsEntry("generationMsP50", 5000L)
        .containsEntry("endToEndMsP50", 5200L);
  }

  @Test
  @DisplayName("改写与多次查询独立计数，环境失败不进入有效统计")
  void countsRewriteAndMultipleQueriesFromSampleFields() {
    Map<String, Object> changed = sample("ANSWERED", false, true, true, 1, 1.0, false);
    changed.put("question", "原问题");
    changed.put("rewrittenQuestion", "改写问题");
    changed.put("attemptedQueries", List.of("改写问题", "原问题"));
    Map<String, Object> unchanged = sample("RETRIEVED", false, false, true, 1, 1.0, false);
    unchanged.put("question", "原问题");
    unchanged.put("rewrittenQuestion", "原问题");
    unchanged.put("attemptedQueries", List.of("原问题"));
    Map<String, Object> failed = sample("HARNESS_ERROR", true, true, null, null, 0, false);
    failed.put("question", "失败问题");
    failed.put("rewrittenQuestion", "失败改写");
    failed.put("attemptedQueries", List.of("失败改写", "失败问题"));
    List<Map<String, Object>> results = List.of(changed, unchanged, failed);

    assertThat(RagEvalMetrics.metricsOf(results))
        .containsEntry("samples", 3)
        .containsEntry("validSamples", 2)
        .containsEntry("inScope", 2)
        .containsEntry("harnessErrorCount", 1)
        .containsEntry("rewriteChangedSamples", 1L)
        .containsEntry("multiQuerySamples", 1L)
        .containsEntry("generationSamples", 1);
    assertThat(RagEvalMetrics.rejectionMetrics(results)).containsEntry("samples", 1);
  }

  @Test
  @DisplayName("没有生成样本时报告零样本数，不伪造生成耗时")
  void noGenerationLatencyWithoutGenerationSamples() {
    Map<String, Object> metrics = RagEvalMetrics.metricsOf(List.of(
        sample("RETRIEVED", false, false, true, 1, 1.0, false)));

    assertThat(metrics).containsEntry("generationSamples", 0)
        .containsEntry("endToEndSamples", 0)
        .doesNotContainKeys("generationMsP50", "generationMsP95", "endToEndMsP50");
  }
}
