package interview.guide.rag;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 测评报告写入器：JSON 供程序化 diff，Markdown 供人工阅读。
 */
public final class RagEvalReportWriter {

  private RagEvalReportWriter() {
  }

  public static void write(Path dir, String runId, Map<String, Object> report) throws IOException {
    Files.createDirectories(dir);
    Path jsonPath = dir.resolve(runId + ".json");
    Path mdPath = dir.resolve(runId + ".md");
    new ObjectMapper().writeValue(jsonPath.toFile(), report);
    Files.writeString(mdPath, toMarkdown(report), StandardCharsets.UTF_8);
  }

  public static void writeHarnessError(Path dir, String runId, String stage, String error) {
    try {
      Files.createDirectories(dir);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("runId", runId);
      report.put("status", "HARNESS_ERROR");
      report.put("failedStage", stage);
      report.put("error", error);
      report.put("timestamp", java.time.Instant.now().toString());
      new ObjectMapper().writeValue(dir.resolve(runId + ".json").toFile(), report);
      Files.writeString(dir.resolve(runId + ".md"),
          "# HARNESS_ERROR\n\n- runId: " + runId + "\n- failedStage: " + stage + "\n- error: " + error + "\n",
          StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      // 报告写入失败不应掩盖原始错误
    }
  }

  @SuppressWarnings("unchecked")
  private static String toMarkdown(Map<String, Object> report) {
    StringBuilder sb = new StringBuilder();
    sb.append("# RAG 测评报告：").append(report.get("runId")).append("\n\n");
    sb.append("- evaluatorVersion: ")
        .append(report.getOrDefault("evaluatorVersion", "legacy（旧版首命中排名口径）"))
        .append("\n\n");
    sb.append("## 运行环境\n\n");
    Map<String, Object> env = (Map<String, Object>) report.get("environment");
    env.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
    sb.append("\n## 汇总指标\n\n");
    sb.append("统计口径：Hit@K、MRR、EvidenceRecall@K 只统计有效的知识库内样本。")
        .append("改写与检索耗时统计全部有效样本，生成与端到端耗时只统计生成子集；")
        .append("各阶段的 Samples 字段给出实际计入耗时的样本数，P50 不能直接相加。\n\n")
        .append("rewriteChangedSamples 按 rewrittenQuestion != question 统计；")
        .append("multiQuerySamples 表示 attemptedQueries 多于一条，可能来自融合或单路回退。\n\n");
    Map<String, Object> metrics = (Map<String, Object>) report.get("metrics");
    metrics.forEach((group, value) -> {
      sb.append("### ").append(group).append("\n\n");
      ((Map<String, Object>) value).forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
      sb.append("\n");
    });
    sb.append("## 拒答混淆矩阵\n\n");
    sb.append("只统计 evaluateGeneration=true 且未发生 HARNESS_ERROR 的样本，")
        .append("样本数等于 tp + fn + fp + tn。\n\n");
    Map<String, Object> reject = (Map<String, Object>) report.get("rejection");
    if (reject != null) {
      reject.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
    }
    sb.append("\n## 坏例\n\n");
    List<Map<String, Object>> bad = (List<Map<String, Object>>) report.getOrDefault("badCases", List.of());
    if (bad.isEmpty()) {
      sb.append("（无）\n");
    }
    for (Map<String, Object> c : bad) {
      sb.append("### ").append(c.get("id")).append(" [").append(c.get("reason")).append("]\n\n");
      sb.append("- question: ").append(c.get("question")).append("\n");
      sb.append("- tags: ").append(c.get("tags")).append("\n");
      sb.append("- detail: ").append(c.get("detail")).append("\n\n");
    }
    sb.append("## 忠实度人工复核对照表（PENDING 待复核）\n\n");
    List<Map<String, Object>> faith = (List<Map<String, Object>>) report.getOrDefault("faithfulnessReview", List.of());
    for (Map<String, Object> f : faith) {
      sb.append("### ").append(f.get("id")).append("\n\n");
      sb.append("- question: ").append(f.get("question")).append("\n");
      sb.append("- expectedEvidence: ").append(f.get("expectedEvidence")).append("\n");
      sb.append("- answer: ").append(f.get("answer")).append("\n");
      sb.append("- faithfulnessStatus: **").append(f.get("faithfulnessStatus")).append("**\n\n");
    }
    sb.append("## 逐样本明细\n\n");
    List<Map<String, Object>> samples = (List<Map<String, Object>>) report.getOrDefault("samples", List.of());
    sb.append("| id | split | tags | outcome | hit | firstRank | evidenceRecall | totalMs |\n");
    sb.append("|---|---|---|---|---|---|---|---|\n");
    for (Map<String, Object> s : samples) {
      sb.append("| ").append(s.get("id"))
          .append(" | ").append(s.get("split"))
          .append(" | ").append(s.get("tags"))
          .append(" | ").append(s.get("outcome"))
          .append(" | ").append(s.get("hit"))
          .append(" | ").append(s.get("firstHitRank"))
          .append(" | ").append(s.get("evidenceRecall"))
          .append(" | ").append(s.get("totalMs"))
          .append(" |\n");
    }
    return sb.toString();
  }
}
