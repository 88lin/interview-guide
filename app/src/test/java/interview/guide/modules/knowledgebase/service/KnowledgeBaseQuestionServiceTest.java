package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.modules.knowledgebase.listener.QuestionGenStreamProducer;
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionGenerationResult;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQuestionService.QuestionDTO;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQuestionService.QuestionListDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseQuestionServiceTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private KnowledgeBaseQuestionRepository questionRepository;
  @Mock
  private KnowledgeBaseVectorService vectorService;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;
  @Mock
  private PromptSanitizer promptSanitizer;
  @Mock
  private ChatClient chatClient;
  @Mock
  private QuestionGenStreamProducer questionGenStreamProducer;
  @Mock
  private QuestionGenerationStateService questionGenerationStateService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private KnowledgeBaseQuestionService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new KnowledgeBaseQuestionService(
        knowledgeBaseRepository,
        questionRepository,
        vectorService,
        llmProviderRegistry,
        structuredOutputInvoker,
        promptSanitizer,
        objectMapper,
        questionGenStreamProducer,
        questionGenerationStateService
    );
    // 用反射注入 @Value 字段，让 service 加载真实模板文件，避免 mock Resource 引发的 StringTemplate 渲染问题
    Resource systemResource = new ClassPathResource("prompts/knowledgebase-question-generation-system.st");
    Resource userResource = new ClassPathResource("prompts/knowledgebase-question-generation-user.st");
    var systemField = KnowledgeBaseQuestionService.class.getDeclaredField("systemPromptResource");
    systemField.setAccessible(true);
    systemField.set(service, systemResource);
    var userField = KnowledgeBaseQuestionService.class.getDeclaredField("userPromptResource");
    userField.setAccessible(true);
    userField.set(service, userResource);

    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(1L);
    kb.setName("Spring Boot 实战");
    kb.setFileHash("hash-1");
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(kb));
    when(promptSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
    when(promptSanitizer.wrapWithDelimiters(anyString(), anyString())).thenReturn("wrapped");
    when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
        .thenReturn(List.of(new Document("知识库片段内容")));
    lenient().when(llmProviderRegistry.getPlainChatClient(nullable(String.class))).thenReturn(chatClient);
  }

  @Nested
  @DisplayName("生成题目的去重逻辑")
  class GenerateDeduplication {

    @Test
    @DisplayName("和已有题完全重复时跳过，不重复的照常入库")
    void shouldSkipDuplicatesAlreadyInDatabase() throws Exception {
      KnowledgeBaseQuestionEntity existing = entity("什么是 JVM 内存模型", "JVM");
      when(questionRepository.findByKnowledgeBase_IdAndDifficulty(1L, "mid"))
          .thenReturn(List.of(existing));
      stubInvoker(buildGeneratedList(
          new QuestionDTO("JVM", null, "什么是 JVM 内存模型", "摘要",
              "参考答案", List.of("要点"), "规则", List.of()),
          new QuestionDTO("JVM", null, "请解释 GC Roots 的作用", "摘要",
              "参考答案", List.of("要点"), "规则", List.of())
      ));
      when(questionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

      KnowledgeBaseQuestionGenerationResult result = service.generateDraftQuestions(1L,
          new GenerateKnowledgeBaseQuestionsRequest("mid", 2, 0, 3, ""));

      assertThat(result.saved()).hasSize(1);
      assertThat(result.saved().get(0).question()).contains("GC Roots");
      assertThat(result.skipped()).isEqualTo(1);
      verify(questionRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("同批生成内题干重复时只保留一道")
    void shouldDeduplicateWithinSameBatch() throws Exception {
      when(questionRepository.findByKnowledgeBase_IdAndDifficulty(1L, "mid"))
          .thenReturn(List.of());
      stubInvoker(buildGeneratedList(
          new QuestionDTO("Spring", null, "什么是依赖注入", "摘要", "参考答案",
              List.of("要点"), "规则", List.of()),
          new QuestionDTO("Spring", null, "什么是 依赖注入", "摘要", "参考答案",
              List.of("要点"), "规则", List.of()),
          new QuestionDTO("Spring", null, "请描述 IoC 容器", "摘要", "参考答案",
              List.of("要点"), "规则", List.of())
      ));
      when(questionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

      KnowledgeBaseQuestionGenerationResult result = service.generateDraftQuestions(1L,
          new GenerateKnowledgeBaseQuestionsRequest("mid", 3, 0, 3, ""));

      assertThat(result.saved()).hasSize(2);
      assertThat(result.saved()).extracting(KnowledgeBaseQuestionDTO::question)
          .containsExactlyInAnyOrder("什么是依赖注入", "请描述 IoC 容器");
      assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("所有生成结果都与已有题重复时返回跳过信息")
    void shouldReturnSkippedResultWhenAllGeneratedAreDuplicates() throws Exception {
      KnowledgeBaseQuestionEntity existing = entity("什么是 JVM 内存模型", "JVM");
      when(questionRepository.findByKnowledgeBase_IdAndDifficulty(1L, "mid"))
          .thenReturn(List.of(existing));
      stubInvoker(buildGeneratedList(
          new QuestionDTO("JVM", null, "什么是 JVM 内存模型？", "摘要",
              "参考答案", List.of("要点"), "规则", List.of())
      ));

      KnowledgeBaseQuestionGenerationResult result = service.generateDraftQuestions(1L,
          new GenerateKnowledgeBaseQuestionsRequest("mid", 1, 0, 3, ""));

      assertThat(result.saved()).isEmpty();
      assertThat(result.skipped()).isEqualTo(1);
      assertThat(result.message()).contains("全部跳过");
    }
  }

  @Nested
  @DisplayName("模型自动归类与方向约束")
  class CategoryAutoFilling {

    @Test
    @DisplayName("模型输出 category 时直接使用")
    void shouldUseModelCategoryWhenProvided() throws Exception {
      when(questionRepository.findByKnowledgeBase_IdAndDifficulty(1L, "mid"))
          .thenReturn(List.of());
      stubInvoker(buildGeneratedList(
          new QuestionDTO("整洁架构", null, "请解释依赖反转原则", "摘要",
              "参考答案", List.of("要点"), "规则", List.of())
      ));
      when(questionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

      KnowledgeBaseQuestionGenerationResult result = service.generateDraftQuestions(1L,
          new GenerateKnowledgeBaseQuestionsRequest("mid", 1, 0, 1, ""));

      assertThat(result.saved()).hasSize(1);
      assertThat(result.saved().get(0).category()).isEqualTo("整洁架构");
      // skillId 写入默认值，不再参与业务
      assertThat(result.saved().get(0).skillId()).isEqualTo(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    }

    @Test
    @DisplayName("模型未输出 category 时用知识库名兜底")
    void shouldFallbackToKnowledgeBaseNameWhenCategoryMissing() throws Exception {
      when(questionRepository.findByKnowledgeBase_IdAndDifficulty(1L, "mid"))
          .thenReturn(List.of());
      stubInvoker(buildGeneratedList(
          new QuestionDTO("  ", null, "请解释依赖反转原则", "摘要",
              "参考答案", List.of("要点"), "规则", List.of())
      ));
      when(questionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

      KnowledgeBaseQuestionGenerationResult result = service.generateDraftQuestions(1L,
          new GenerateKnowledgeBaseQuestionsRequest("mid", 1, 0, 1, ""));

      assertThat(result.saved().get(0).category()).isEqualTo("Spring Boot 实战");
    }

    @Test
    @DisplayName("生成时把已有方向传给 prompt，让模型优先复用")
    @SuppressWarnings("unchecked")
    void shouldPassExistingCategoriesToPrompt() throws Exception {
      when(questionRepository.findCategoryCounts(1L)).thenReturn(List.of(
          stubCategoryCount("JVM", 5L),
          stubCategoryCount("Spring", 3L)
      ));
      when(questionRepository.findTop20ByKnowledgeBase_IdAndDifficultyOrderByUpdatedAtDesc(1L, "mid"))
          .thenReturn(List.of(entity("已有的 JVM 问题", "JVM")));
      when(questionRepository.findByKnowledgeBase_IdAndDifficulty(1L, "mid"))
          .thenReturn(List.of());
      stubInvoker(buildGeneratedList(
          new QuestionDTO("JVM", null, "什么是内存模型", "摘要",
              "参考答案", List.of("要点"), "规则", List.of())
      ));
      when(questionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

      service.generateDraftQuestions(1L,
          new GenerateKnowledgeBaseQuestionsRequest("mid", 1, 0, 1, ""));

      ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
      ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
      verify(structuredOutputInvoker).invoke(
          eq(chatClient), systemCaptor.capture(), userCaptor.capture(),
          any(), any(), anyString(), anyString(), any());
      assertThat(userCaptor.getValue()).contains("JVM（5 题）");
      assertThat(userCaptor.getValue()).contains("Spring（3 题）");
      assertThat(userCaptor.getValue()).contains("已有的 JVM 问题");
    }
  }

  @Nested
  @DisplayName("listQuestions 筛选")
  class ListFiltering {

    @Test
    @DisplayName("按 category 筛选时只返回该方向的题")
    void shouldFilterByCategory() {
      KnowledgeBaseQuestionEntity redis = entity("Redis 主问题", "Redis");
      KnowledgeBaseQuestionEntity jvm = entity("JVM 主问题", "JVM");
      when(questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(1L))
          .thenReturn(List.of(redis, jvm));

      List<KnowledgeBaseQuestionDTO> result =
          service.listQuestions(1L, null, "Redis", null, null);

      assertThat(result).hasSize(1);
      assertThat(result.get(0).category()).isEqualTo("Redis");
    }

    @Test
    @DisplayName("category 为空白时返回全部")
    void shouldReturnAllWhenCategoryIsBlank() {
      when(questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(1L))
          .thenReturn(List.of(entity("Q1", "JVM"), entity("Q2", "Redis")));

      List<KnowledgeBaseQuestionDTO> result =
          service.listQuestions(1L, null, "  ", null, null);

      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("兼容字符串数组格式的历史追问")
    void shouldReadLegacyStringFollowUps() throws Exception {
      KnowledgeBaseQuestionEntity question = entity("Q1", "JVM");
      question.setFollowUpsJson(objectMapper.writeValueAsString(List.of("追问1")));
      when(questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(1L))
          .thenReturn(List.of(question));

      List<KnowledgeBaseQuestionDTO> result =
          service.listQuestions(1L, null, null, null, null);

      assertThat(result.get(0).followUps()).hasSize(1);
      assertThat(result.get(0).followUps().get(0).question()).isEqualTo("追问1");
    }
  }

  private void stubInvoker(QuestionListDTO toReturn) {
    when(structuredOutputInvoker.invoke(
        eq(chatClient), anyString(), anyString(), any(),
        any(), anyString(), anyString(), any()))
        .thenReturn(toReturn);
  }

  private QuestionListDTO buildGeneratedList(QuestionDTO... questions) {
    return new QuestionListDTO(List.of(questions));
  }

  private KnowledgeBaseQuestionEntity entity(String question, String category) {
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(1L);
    kb.setName("Spring Boot 实战");
    KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
    entity.setKnowledgeBase(kb);
    entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    entity.setDifficulty("mid");
    entity.setCategory(category);
    entity.setQuestion(question);
    entity.setStatus(KnowledgeBaseQuestionStatus.DRAFT);
    return entity;
  }

  private CategoryCount stubCategoryCount(String category, Long count) {
    return new CategoryCount() {
      @Override
      public String getCategory() {
        return category;
      }

      @Override
      public Long getCount() {
        return count;
      }
    };
  }
}
