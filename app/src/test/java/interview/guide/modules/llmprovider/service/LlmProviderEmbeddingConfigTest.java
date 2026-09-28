package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("向量配置不能按模型系列名称误判能力")
class LlmProviderEmbeddingConfigTest {

  private final LlmProviderProperties properties = new LlmProviderProperties();
  private final LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
  private final LlmProviderRepository providers = mock(LlmProviderRepository.class);
  private final LlmGlobalSettingRepository settings = mock(LlmGlobalSettingRepository.class);
  private final ApiKeyEncryptionService encryption = mock(ApiKeyEncryptionService.class);
  private LlmProviderConfigService service;

  @BeforeEach
  void setUp() {
    service = new LlmProviderConfigService(properties, registry, providers, settings,
        encryption, new VoiceInterviewProperties(), null, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"qwen3-embedding-0.6b", "Qwen/Qwen3-Embedding-8B", "glm-vector-deployment"})
  @DisplayName("创建配置保留向量模型名及自定义部署别名")
  void createEmbeddingProvider(String model) {
    when(encryption.encrypt("test-key"))
        .thenReturn(new ApiKeyEncryptionService.EncryptedValue("nonce", "ciphertext"));

    service.createProvider(request(model));

    ArgumentCaptor<LlmProviderEntity> saved = ArgumentCaptor.forClass(LlmProviderEntity.class);
    verify(providers).save(saved.capture());
    assertThat(saved.getValue().getEmbeddingModel()).isEqualTo(model);
    assertThat(saved.getValue().getEmbeddingDimensions()).isEqualTo(1024);
    assertThat(saved.getValue().isSupportsEmbedding()).isTrue();
    verify(registry).reload();
  }

  @Test
  @DisplayName("更新已有配置允许同系列向量模型")
  void updateEmbeddingProvider() {
    LlmProviderEntity provider = provider(true, "text-embedding-v3");
    when(providers.findById("probe")).thenReturn(Optional.of(provider));

    service.updateProvider("probe",
        new UpdateProviderRequest(null, null, null, "qwen3-embedding-0.6b", 1024, true, null));

    verify(providers).save(provider);
    assertThat(provider.getEmbeddingModel()).isEqualTo("qwen3-embedding-0.6b");
    verify(registry).reload();
  }

  @Test
  @DisplayName("同系列向量模型可以设为默认向量服务")
  void selectDefaultEmbeddingProvider() {
    when(providers.findById("probe"))
        .thenReturn(Optional.of(provider(true, "qwen3-embedding-0.6b")));
    LlmGlobalSettingEntity setting = LlmGlobalSettingEntity.builder().id(1L).build();
    when(settings.findById(1L)).thenReturn(Optional.of(setting));

    service.updateDefaultEmbeddingProvider(new DefaultProviderDTO(null, "probe"));

    verify(settings).save(setting);
    assertThat(setting.getDefaultEmbeddingProviderId()).isEqualTo("probe");
  }

  @Test
  @DisplayName("开启向量能力时仍拒绝空模型名")
  void rejectMissingEmbeddingModel() {
    assertThatThrownBy(() -> service.createProvider(request("  ")))
        .isInstanceOf(BusinessException.class).hasMessageContaining("embeddingModel");
    verify(providers, never()).save(any());
  }

  @Test
  @DisplayName("向量维度仍必须为正整数")
  void rejectInvalidEmbeddingDimensions() {
    properties.setEmbeddingDimensions(0);
    CreateProviderRequest request = new CreateProviderRequest("probe", "https://example.com/v1",
        "test-key", "chat-model", "qwen3-embedding-0.6b", null, true, null);
    assertThatThrownBy(() -> service.createProvider(request))
        .isInstanceOf(BusinessException.class).hasMessageContaining("向量维度必须为正整数");
    verify(providers, never()).save(any());
  }

  @Test
  @DisplayName("未启用向量能力的服务仍不能设为默认向量服务")
  void rejectDisabledEmbeddingProvider() {
    when(providers.findById("probe"))
        .thenReturn(Optional.of(provider(false, "qwen3-embedding-0.6b")));
    assertThatThrownBy(() -> service.updateDefaultEmbeddingProvider(new DefaultProviderDTO(null, "probe")))
        .isInstanceOf(BusinessException.class).hasMessageContaining("不支持 Embedding");
    verify(settings, never()).save(any());
  }

  private CreateProviderRequest request(String model) {
    return new CreateProviderRequest("probe", "https://example.com/v1", "test-key",
        "chat-model", model, 1024, true, null);
  }

  private LlmProviderEntity provider(boolean supportsEmbedding, String model) {
    return LlmProviderEntity.builder().id("probe").enabled(true)
        .supportsEmbedding(supportsEmbedding).embeddingModel(model).embeddingDimensions(1024).build();
  }
}
