package interview.guide.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG 测评指标计算（纯函数，便于单元测试）。
 *
 * <p>约定：逐样本结果为 Map，字段与 RagEvaluationTest 写入一致：
 * split、tags、shouldReject、outcome、hit、firstHitRank、evidenceRecall、
 * predictedReject、evaluateGeneration、rewriteMs、retrievalMs、generationMs、totalMs。
 */
public final class RagEvalMetrics {

  private RagEvalMetrics() {
  }

  /**
   * 汇总一组样本的指标。HARNESS_ERROR 样本被排除并单独计数。
   */
  public static Map<String, Object> metricsOf(List<Map<String, Object>> results) {
    Map<String, Object> m = new LinkedHashMap<>();
    List<Map<String, Object>> valid = new ArrayList<>();
    int harnessErrors = 0;
    for (Map<String, Object> r : results) {
      if ("HARNESS_ERROR".equals(r.get("outcome"))) {
        harnessErrors++;
      } else {
        valid.add(r);
      }
    }
    if (results.isEmpty()) {
      return m;
    }
    m.put("samples", results.size());
    m.put("harnessErrorCount", harnessErrors);
    m.put("validSamples", valid.size());
    m.put("rewriteChangedSamples", valid.stream()
        .filter(r -> r.get("question") instanceof String original
            && r.get("rewrittenQuestion") instanceof String rewritten
            && !original.equals(rewritten)).count());
    m.put("multiQuerySamples", valid.stream()
        .filter(r -> r.get("attemptedQueries") instanceof List<?> queries
            && queries.size() > 1).count());
    List<Map<String, Object>> inScope = valid.stream()
        .filter(r -> !Boolean.TRUE.equals(r.get("shouldReject"))).toList();
    if (!inScope.isEmpty()) {
      m.put("inScope", inScope.size());
      m.put("Hit@K(%)", round(100.0 * inScope.stream()
          .filter(r -> Boolean.TRUE.equals(r.get("hit"))).count() / inScope.size()));
      // MRR：未命中样本贡献 0
      m.put("MRR", round(inScope.stream()
          .mapToDouble(r -> {
            Integer rank = (Integer) r.get("firstHitRank");
            return rank == null ? 0.0 : 1.0 / rank;
          }).average().orElse(0)));
      m.put("EvidenceRecall@K", round(inScope.stream()
          .mapToDouble(r -> r.get("evidenceRecall") instanceof Number n ? n.doubleValue() : 0.0)
          .average().orElse(0)));
    }
    putStagePercentiles(m, "retrieval", valid, "retrievalMs");
    putStagePercentiles(m, "rewrite", valid, "rewriteMs");
    List<Map<String, Object>> generation = valid.stream()
        .filter(r -> Boolean.TRUE.equals(r.get("evaluateGeneration"))).toList();
    putStagePercentiles(m, "generation", generation, "generationMs");
    putStagePercentiles(m, "endToEnd", generation, "totalMs");
    return m;
  }

  /**
   * 拒答混淆矩阵与 Accuracy/Precision/Recall/F1。
   * 矩阵只统计 evaluateGeneration=true 的样本：actual=shouldReject，predicted=predictedReject。
   * 返回键：tp、fn、fp、tn、accuracy、precision、recall、f1。
   */
  public static Map<String, Object> rejectionMetrics(List<Map<String, Object>> results) {
    int tp = 0;
    int fn = 0;
    int fp = 0;
    int tn = 0;
    for (Map<String, Object> r : results) {
      if (!Boolean.TRUE.equals(r.get("evaluateGeneration"))
          || "HARNESS_ERROR".equals(r.get("outcome"))) {
        continue;
      }
      boolean actual = Boolean.TRUE.equals(r.get("shouldReject"));
      boolean predicted = Boolean.TRUE.equals(r.get("predictedReject"));
      if (actual && predicted) {
        tp++;
      } else if (actual) {
        fn++;
      } else if (predicted) {
        fp++;
      } else {
        tn++;
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tp", tp);
    m.put("fn", fn);
    m.put("fp", fp);
    m.put("tn", tn);
    int total = tp + fn + fp + tn;
    m.put("samples", total);
    m.put("accuracy", total == 0 ? 0 : round((double) (tp + tn) / total));
    m.put("precision", tp + fp == 0 ? 0 : round((double) tp / (tp + fp)));
    m.put("recall", tp + fn == 0 ? 0 : round((double) tp / (tp + fn)));
    double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
    double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
    m.put("f1", precision + recall == 0 ? 0 : round(2 * precision * recall / (precision + recall)));
    return m;
  }

  private static void putStagePercentiles(Map<String, Object> m, String stage,
                                          List<Map<String, Object>> results, String field) {
    List<Long> values = results.stream()
        .map(r -> r.get(field))
        .filter(Objects::nonNull)
        .map(v -> ((Number) v).longValue())
        .sorted().toList();
    m.put(stage + "Samples", values.size());
    if (!values.isEmpty()) {
      m.put(stage + "MsP50", percentile(values, 0.50));
      m.put(stage + "MsP95", percentile(values, 0.95));
    }
  }

  private static long percentile(List<Long> sorted, double p) {
    int index = (int) Math.min(sorted.size() - 1L, Math.round(p * (sorted.size() - 1)));
    return sorted.get(index);
  }

  private static double round(double value) {
    return Math.round(value * 10000) / 10000.0;
  }
}
