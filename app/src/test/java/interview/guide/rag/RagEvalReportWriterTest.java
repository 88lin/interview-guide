package interview.guide.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 评测报告统计口径")
class RagEvalReportWriterTest {

  @TempDir
  Path directory;

  @Test
  @DisplayName("JSON 与 Markdown 均保留评测器版本和样本数，Markdown 解释统计范围")
  void reportExplainsDifferentSamplePopulations() throws IOException {
    Map<String, Object> report = Map.of(
        "runId", "test-run",
        "evaluatorVersion", "evidence-rank-v2",
        "environment", Map.of("chunkSize", 400),
        "metrics", Map.of("overall", Map.of("samples", 40, "inScope", 32,
            "generationSamples", 18, "rewriteChangedSamples", 23)),
        "rejection", Map.of("samples", 18, "tp", 0, "fn", 8, "fp", 0, "tn", 10),
        "samples", List.of());

    RagEvalReportWriter.write(directory, "test-run", report);

    assertThat(Files.readString(directory.resolve("test-run.md")))
        .contains("evaluatorVersion: evidence-rank-v2", "generationSamples: 18",
            "rewriteChangedSamples: 23", "P50 不能直接相加", "tp + fn + fp + tn");
    Map<?, ?> json = new ObjectMapper().readValue(directory.resolve("test-run.json").toFile(), Map.class);
    assertThat(json.get("evaluatorVersion")).isEqualTo("evidence-rank-v2");
    assertThat(json.get("rejection")).isEqualTo(report.get("rejection"));
  }
}
