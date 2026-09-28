package interview.guide.rag;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorProperties;
import interview.guide.modules.knowledgebase.service.RagQueryExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RAG 逐样本评测（模拟检索，不调用模型）")
class RagEvaluationSampleTest {

  @Test
  @DisplayName("快照保存已绑定的分块、检索和历史配置，不猜测环境变量默认值")
  void snapshotsEffectiveConfiguration() {
    KnowledgeBaseQueryProperties query = new KnowledgeBaseQueryProperties();
    query.getRewrite().setEnabled(false);
    query.getSearch().setMergeOriginalQuery(true);
    query.getSearch().setTopkLong(6);
    query.getHistory().setMaxMessages(4);
    KnowledgeBaseVectorProperties vector = new KnowledgeBaseVectorProperties();
    vector.setChunkSize(400);
    vector.setPunctuationMarks(List.of("。", "；"));
    RagEvaluationTest evaluator = new RagEvaluationTest();
    ReflectionTestUtils.setField(evaluator, "queryProperties", query);
    ReflectionTestUtils.setField(evaluator, "vectorProperties", vector);

    Map<String, Object> snapshot = ReflectionTestUtils.invokeMethod(
        evaluator, "configurationSnapshot");

    assertThat(snapshot).containsEntry("rewriteEnabled", false)
        .containsEntry("mergeOriginalQuery", true).containsEntry("chunkSize", 400);
    Map<?, ?> vectorization = (Map<?, ?>) snapshot.get("vectorization");
    assertThat(vectorization.get("chunkSize")).isEqualTo(400);
    assertThat(vectorization.get("punctuationMarks")).isEqualTo(List.of("。", "；"));
    assertThat(((Map<?, ?>) snapshot.get("search")).get("topkLong")).isEqualTo(6);
    assertThat(((Map<?, ?>) snapshot.get("history")).get("maxMessages")).isEqualTo(4);
  }

  @Test
  @DisplayName("首命中取所有证据的最小排名，不受证据标注顺序影响")
  void firstHitRankIsIndependentOfEvidenceOrder() {
    RagEvalSample.Evidence first = new RagEvalSample.Evidence("first", "排名靠前的证据");
    RagEvalSample.Evidence later = new RagEvalSample.Evidence("later", "排名靠后的证据");
    KnowledgeBaseQueryService service = mock(KnowledgeBaseQueryService.class);
    when(service.retrieveOnly(List.of(1L), "测试问题", List.of())).thenReturn(
        new RagQueryExecution("测试问题", "测试问题", List.of("测试问题"), 8, 0.28,
            List.of(
                new RagQueryExecution.RetrievedDoc(1, first.text(), 0.9, Map.of()),
                new RagQueryExecution.RetrievedDoc(2, "无关内容", 0.8, Map.of()),
                new RagQueryExecution.RetrievedDoc(3, later.text(), 0.7, Map.of())),
            0, 100, 0, "", "RETRIEVED"));
    RagEvaluationTest evaluator = new RagEvaluationTest();
    ReflectionTestUtils.setField(evaluator, "queryService", service);
    ReflectionTestUtils.setField(evaluator, "fixtureKbIds", Map.of("test.md", 1L));

    for (List<RagEvalSample.Evidence> evidence : List.of(List.of(later, first), List.of(first, later))) {
      RagEvalSample sample = new RagEvalSample("chunk-test", "测试问题", List.of(),
          "test.md", evidence, false, List.of("跨段题"), "dev", false);
      Map<String, Object> result = ReflectionTestUtils.invokeMethod(
          evaluator, "evaluateSample", sample, new ArrayList<>(), new ArrayList<>());

      assertThat(result).containsEntry("outcome", "RETRIEVED")
          .containsEntry("hit", true)
          .containsEntry("firstHitRank", 1)
          .containsEntry("evidenceRecall", 1.0);
      assertThat(RagEvalMetrics.metricsOf(List.of(result))).containsEntry("MRR", 1.0);
    }
  }
}
