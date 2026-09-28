package interview.guide.common.ai;

import com.sun.net.httpserver.HttpServer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("向量模型能力通过 Embedding 接口验证")
class LlmProviderEmbeddingIntegrationTest {

  private HttpServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private int responseStatus = 200;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String body = responseStatus == 200 ? """
          {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],
           "model":"probe","usage":{"prompt_tokens":1,"total_tokens":1}}
          """ : """
          {"error":{"message":"model does not support embeddings","type":"invalid_request_error"}}
          """;
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(responseStatus, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"qwen3-embedding-0.6b", "Qwen/Qwen3-Embedding-8B", "glm-vector-deployment"})
  @DisplayName("同系列名称及自定义别名实际请求向量端点并返回向量")
  void embedWithSharedFamilyName(String model) {
    float[] vector = registry(model, true).getEmbeddingModel("probe").embed("测试文本");

    assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
    assertThat(paths).containsExactly("/v1/embeddings");
    assertThat(requests).singleElement().asString().contains(model).contains("测试文本");
  }

  @Test
  @DisplayName("实际不支持向量接口时传播上游错误而非伪造成功")
  void rejectModelWithoutEmbeddingCapability() {
    responseStatus = 400;
    assertThatThrownBy(() -> registry("qwen-chat-deployment", true)
        .getEmbeddingModel("probe").embed("测试文本"))
        .hasMessageContaining("model does not support embeddings");
    assertThat(paths).containsExactly("/v1/embeddings");
  }

  @Test
  @DisplayName("未配置向量模型仍在调用前失败")
  void rejectMissingModel() {
    assertThatThrownBy(() -> registry(" ", true).getEmbeddingModel("probe"))
        .isInstanceOf(BusinessException.class).hasMessageContaining("未配置可用的 Embedding 模型");
    assertThat(paths).isEmpty();
  }

  @Test
  @DisplayName("未启用向量能力仍在调用前失败")
  void rejectDisabledCapability() {
    assertThatThrownBy(() -> registry(null, false).getEmbeddingModel("probe"))
        .isInstanceOf(BusinessException.class).hasMessageContaining("未配置可用的 Embedding 模型");
    assertThat(paths).isEmpty();
  }

  private LlmProviderRegistry registry(String model, boolean supportsEmbedding) {
    LlmProviderProperties properties = new LlmProviderProperties();
    ProviderConfig config = new ProviderConfig();
    config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    config.setApiKey("test-key");
    config.setModel("chat-model");
    config.setEmbeddingModel(model);
    config.setEmbeddingDimensions(3);
    config.setSupportsEmbedding(supportsEmbedding);
    properties.setProviders(Map.of("probe", config));
    return new LlmProviderRegistry(properties, null, null, null);
  }
}
