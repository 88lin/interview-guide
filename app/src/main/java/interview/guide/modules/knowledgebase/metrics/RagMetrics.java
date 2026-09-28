package interview.guide.modules.knowledgebase.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * RAG 业务指标统一封装（P1-04）。
 *
 * <p>固定 Meter 名称与有限标签，Service 不直接接触 MeterRegistry。
 * 标签只允许 mode/result/stage/variant/reason 五个低基数键，
 * 禁止用户 ID、会话 ID、知识库 ID、问题文本、异常消息与 Provider URL。
 *
 * <p>关闭开关（app.ai.rag.metrics-enabled=false）或 Registry 缺失时全部 no-op。
 * 底层模型 HTTP 指标已由 Spring AI Observation 覆盖，这里只补业务阶段。
 */
@Component
public class RagMetrics {

  public static final String REQUESTS = "app.rag.requests";
  public static final String STAGE_DURATION = "app.rag.stage.duration";
  public static final String RETRIEVAL_HITS = "app.rag.retrieval.hits";
  public static final String REWRITE_FALLBACKS = "app.rag.rewrite.fallbacks";
  public static final String REFUSALS = "app.rag.refusals";

  private final MeterRegistry registry;
  private final boolean enabled;

  public RagMetrics(ObjectProvider<MeterRegistry> registryProvider,
                     KnowledgeBaseQueryProperties queryProperties) {
    this.registry = registryProvider != null ? registryProvider.getIfAvailable() : null;
    this.enabled = queryProperties.isMetricsEnabled() && this.registry != null;
  }

  public boolean enabled() {
    return enabled;
  }

  /** 请求结果：mode=sync|stream，result=success|reject|error|cancel，一次请求只允许一次 */
  public void recordRequest(String mode, String result) {
    if (enabled) {
      registry.counter(REQUESTS, "mode", mode, "result", result).increment();
    }
  }

  /** 阶段耗时：stage=rewrite|retrieve|generate|total */
  public void recordStageDuration(String stage, String result, long durationNanos) {
    if (enabled) {
      Timer.builder(STAGE_DURATION)
          .tag("stage", stage)
          .tag("result", result)
          .publishPercentileHistogram()
          .register(registry)
          .record(java.time.Duration.ofNanos(durationNanos));
    }
  }

  /** 检索命中数分布：variant=rewritten|original|single|merged */
  public void recordRetrievalHits(String variant, int hits) {
    if (enabled) {
      DistributionSummary.builder(RETRIEVAL_HITS)
          .tag("variant", variant)
          .register(registry)
          .record(hits);
    }
  }

  /** 改写回退：reason=disabled|blank|error|unchanged；成功改写不记 */
  public void recordRewriteFallback(String reason) {
    if (enabled) {
      registry.counter(REWRITE_FALLBACKS, "reason", reason).increment();
    }
  }

  /** 拒答：reason=invalid_request|no_hit；异常与取消不记 */
  public void recordRefusal(String reason) {
    if (enabled) {
      registry.counter(REFUSALS, "reason", reason).increment();
    }
  }
}
