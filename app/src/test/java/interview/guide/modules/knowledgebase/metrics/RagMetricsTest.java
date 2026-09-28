package interview.guide.modules.knowledgebase.metrics;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagMetrics 单元测试（SimpleMeterRegistry，不接触业务 Service）。
 */
@DisplayName("RAG 业务指标")
class RagMetricsTest {

  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
  }

  @SuppressWarnings("unchecked")
  private RagMetrics metrics(boolean enabled) {
    return metrics(enabled, registry);
  }

  @SuppressWarnings("unchecked")
  private RagMetrics metrics(boolean enabled, MeterRegistry targetRegistry) {
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(targetRegistry);
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.setMetricsEnabled(enabled);
    return new RagMetrics(provider, properties);
  }

  @Test
  @DisplayName("Prometheus 导出阶段耗时桶，支持文章中的 histogram_quantile 查询")
  void exportsStageDurationHistogram() {
    PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try {
      RagMetrics metrics = metrics(true, prometheus);
      metrics.recordStageDuration("retrieve", "success", 10_000_000L);

      assertThat(prometheus.scrape())
          .contains("app_rag_stage_duration_seconds_bucket{")
          .contains("le=\"+Inf\"")
          .contains("stage=\"retrieve\"");
    } finally {
      prometheus.close();
    }
  }

  @Test
  @DisplayName("五类指标全部可记录且标签键在白名单内")
  void recordsAllMeterTypes() {
    RagMetrics metrics = metrics(true);

    metrics.recordRequest("sync", "success");
    metrics.recordStageDuration("retrieve", "success", 1_000_000L);
    metrics.recordRetrievalHits("single", 8);
    metrics.recordRewriteFallback("disabled");
    metrics.recordRefusal("no_hit");

    assertThat(registry.get(RagMetrics.REQUESTS).counter().count()).isEqualTo(1.0);
    assertThat(registry.get(RagMetrics.STAGE_DURATION).timer().count()).isEqualTo(1L);
    assertThat(registry.get(RagMetrics.RETRIEVAL_HITS).summary().count()).isEqualTo(1L);
    assertThat(registry.get(RagMetrics.REWRITE_FALLBACKS).counter().count()).isEqualTo(1.0);
    assertThat(registry.get(RagMetrics.REFUSALS).counter().count()).isEqualTo(1.0);

    registry.getMeters().forEach(meter ->
        assertThat(meter.getId().getTags()).allSatisfy(tag ->
            assertThat(tag.getKey()).isIn("mode", "result", "stage", "variant", "reason")));
  }

  @Test
  @DisplayName("metrics-enabled=false 时不产生任何 Meter")
  void noMetersWhenDisabled() {
    RagMetrics metrics = metrics(false);

    metrics.recordRequest("sync", "success");
    metrics.recordStageDuration("total", "success", 1L);
    metrics.recordRetrievalHits("single", 1);
    metrics.recordRewriteFallback("error");
    metrics.recordRefusal("invalid_request");

    assertThat(registry.getMeters()).isEmpty();
  }

  @Test
  @DisplayName("Registry 缺失时全部 no-op 不抛异常")
  void noOpWithoutRegistry() {
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    RagMetrics metrics = new RagMetrics(null, properties);

    metrics.recordRequest("stream", "cancel");
    metrics.recordStageDuration("generate", "cancel", 1L);

    // 无异常即通过
  }
}
