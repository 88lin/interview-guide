package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionGenerationResult;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionService {

  private static final int RETRIEVAL_TOP_K = 12;
  private static final int RETRIEVAL_QUERY_TOP_K = 4;
  private static final int MAX_CONTEXT_CHARS = 5000;
  private static final int DEFAULT_FOLLOW_UP_COUNT = 2;
  private static final int DEFAULT_CATEGORY_LIMIT = 3;
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
  };
  private static final TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>> FOLLOW_UP_LIST_TYPE =
      new TypeReference<>() {
      };

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final KnowledgeBaseVectorService vectorService;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PromptSanitizer promptSanitizer;
  private final ObjectMapper objectMapper;

  @Value("classpath:prompts/knowledgebase-question-generation-system.st")
  private Resource systemPromptResource;

  @Value("classpath:prompts/knowledgebase-question-generation-user.st")
  private Resource userPromptResource;

  private final BeanOutputConverter<QuestionListDTO> outputConverter =
      new BeanOutputConverter<>(QuestionListDTO.class);

  // 包级可见以便单测构造；不暴露到 service 外部
  record QuestionListDTO(List<QuestionDTO> questions) {
  }

  record QuestionDTO(String category,
                             String type,
                             String question,
                             String topicSummary,
                             String referenceAnswer,
                             List<String> keyPoints,
                             String scoringRubric,
                             List<KnowledgeBaseQuestionFollowUpDTO> followUps) {
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseQuestionDTO> listQuestions(Long knowledgeBaseId,
                                                      KnowledgeBaseQuestionStatus status,
                                                      String category,
                                                      String difficulty,
                                                      String keyword) {
    List<KnowledgeBaseQuestionEntity> questions = status == null
        ? questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(knowledgeBaseId)
        : questionRepository.findByKnowledgeBase_IdAndStatusOrderByUpdatedAtDesc(knowledgeBaseId, status);
    String categoryFilter = trimToNull(category);
    String difficultyFilter = trimToNull(difficulty);
    String keywordFilter = trimToNull(keyword);
    return questions.stream()
        .filter(q -> categoryFilter == null || categoryFilter.equals(q.getCategory()))
        .filter(q -> difficultyFilter == null || difficultyFilter.equals(q.getDifficulty()))
        .filter(q -> keywordFilter == null || containsKeyword(q, keywordFilter))
        .map(this::toDTO)
        .toList();
  }

  /**
   * 列出某知识库下出现过的方向（含题目计数），按出现频次降序。
   * 用于前端筛选下拉、开始面试弹窗的方向选择。
   */
  @Transactional(readOnly = true)
  public List<CategoryCount> listCategories(Long knowledgeBaseId) {
    return questionRepository.findCategoryCounts(knowledgeBaseId);
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO createQuestion(Long knowledgeBaseId,
                                                 CreateKnowledgeBaseQuestionRequest request) {
    KnowledgeBaseEntity kb = getKnowledgeBase(knowledgeBaseId);
    KnowledgeBaseQuestionEntity question = new KnowledgeBaseQuestionEntity();
    question.setKnowledgeBase(kb);
    applyCreateRequest(question, request);
    question.setKbContentHash(kb.getFileHash());
    question.setStatus(request.status() != null ? request.status() : KnowledgeBaseQuestionStatus.DRAFT);
    return toDTO(questionRepository.save(question));
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO updateQuestion(Long questionId, UpdateKnowledgeBaseQuestionRequest request) {
    KnowledgeBaseQuestionEntity question = getQuestion(questionId);
    if (request.difficulty() != null) {
      question.setDifficulty(normalizeDifficulty(request.difficulty()));
    }
    if (request.type() != null) {
      question.setType(trimToNull(request.type()));
    }
    if (request.category() != null) {
      if (request.category().isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "面试方向不能为空");
      }
      question.setCategory(request.category().trim());
    }
    if (request.question() != null) {
      if (request.question().isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "题干不能为空");
      }
      question.setQuestion(request.question().trim());
    }
    if (request.topicSummary() != null) {
      question.setTopicSummary(trimToNull(request.topicSummary()));
    }
    if (request.referenceAnswer() != null) {
      question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
    }
    if (request.keyPoints() != null) {
      question.setKeyPointsJson(writeStringList(request.keyPoints()));
    }
    if (request.scoringRubric() != null) {
      question.setScoringRubric(trimToNull(request.scoringRubric()));
    }
    if (request.followUps() != null) {
      question.setFollowUpsJson(writeFollowUps(request.followUps()));
    }
    if (request.sourceContext() != null) {
      question.setSourceContext(trimToNull(request.sourceContext()));
    }
    if (request.status() != null) {
      question.setStatus(request.status());
    }
    return toDTO(questionRepository.save(question));
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO updateStatus(Long questionId, KnowledgeBaseQuestionStatus status) {
    KnowledgeBaseQuestionEntity question = getQuestion(questionId);
    question.setStatus(status);
    return toDTO(questionRepository.save(question));
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteQuestion(Long questionId) {
    if (!questionRepository.existsById(questionId)) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
    }
    questionRepository.deleteById(questionId);
  }

  public KnowledgeBaseQuestionGenerationResult generateDraftQuestions(
      Long knowledgeBaseId,
      GenerateKnowledgeBaseQuestionsRequest request) {
    KnowledgeBaseEntity kb = getKnowledgeBase(knowledgeBaseId);
    String difficulty = normalizeDifficulty(request.difficulty());
    int followUpCount = request.followUpCount() == null
        ? DEFAULT_FOLLOW_UP_COUNT
        : Math.max(0, Math.min(request.followUpCount(), 5));
    int categoryLimit = request.categoryLimit() == null
        ? DEFAULT_CATEGORY_LIMIT
        : Math.max(1, Math.min(request.categoryLimit(), 5));
    String context = buildGenerationContext(kb);
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(request.llmProvider());

    try {
      String systemPrompt = loadTemplate(systemPromptResource).render()
          + "\n\n"
          + outputConverter.getFormat();
      String userPrompt = loadTemplate(userPromptResource)
          .render(Map.of(
              "knowledgeBaseName", kb.getName(),
              "difficulty", difficulty,
              "questionCount", Math.max(1, request.questionCount()),
              "followUpCount", followUpCount,
              "categoryLimit", categoryLimit,
              "existingCategories", buildExistingCategorySection(knowledgeBaseId),
              "existingQuestions", promptSanitizer.sanitize(
                  buildExistingQuestionSection(knowledgeBaseId, difficulty)),
              "context", PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                  + promptSanitizer.wrapWithDelimiters("knowledge-base",
                      promptSanitizer.sanitize(context))
          ));

      QuestionListDTO generated = structuredOutputInvoker.invoke(
          chatClient,
          systemPrompt,
          userPrompt,
          outputConverter,
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "知识库题库生成失败：",
          "知识库题库生成",
          log
      );
      return saveGeneratedQuestions(kb, difficulty, context, generated);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("知识库题库生成失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "知识库题库生成失败：" + e.getMessage());
    }
  }

  private KnowledgeBaseQuestionGenerationResult saveGeneratedQuestions(KnowledgeBaseEntity kb,
                                                                       String difficulty,
                                                                       String sourceContext,
                                                                       QuestionListDTO generated) {
    if (generated == null || generated.questions() == null || generated.questions().isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果为空");
    }

    // 已入库题干（同知识库 + 同难度）的归一化集合
    Set<String> existingKeys = questionRepository
        .findByKnowledgeBase_IdAndDifficulty(kb.getId(), difficulty).stream()
        .map(q -> normalizeQuestionKey(q.getQuestion()))
        .collect(Collectors.toCollection(HashSet::new));

    List<KnowledgeBaseQuestionEntity> entities = new ArrayList<>();
    Set<String> batchKeys = new LinkedHashSet<>();
    int skippedDuplicates = 0;
    for (QuestionDTO dto : generated.questions()) {
      if (dto == null || dto.question() == null || dto.question().isBlank()) {
        continue;
      }
      String rawQuestion = dto.question().trim();
      String key = normalizeQuestionKey(rawQuestion);
      if (existingKeys.contains(key) || !batchKeys.add(key)) {
        skippedDuplicates += 1;
        continue;
      }
      String category = normalizeCategory(dto.category(), kb.getName());
      KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
      entity.setKnowledgeBase(kb);
      entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
      entity.setDifficulty(difficulty);
      entity.setType(trimToNull(dto.type()));
      entity.setCategory(category);
      entity.setQuestion(rawQuestion);
      entity.setTopicSummary(trimToNull(dto.topicSummary()));
      entity.setReferenceAnswer(trimToNull(dto.referenceAnswer()));
      entity.setKeyPointsJson(writeStringList(dto.keyPoints()));
      entity.setScoringRubric(trimToNull(dto.scoringRubric()));
      entity.setFollowUpsJson(writeFollowUps(dto.followUps()));
      entity.setSourceContext(sourceContext);
      entity.setKbContentHash(kb.getFileHash());
      entity.setStatus(KnowledgeBaseQuestionStatus.DRAFT);
      entities.add(entity);
    }
    if (entities.isEmpty()) {
      if (skippedDuplicates > 0) {
        String message = String.format("本次生成结果均与已有题重复（共 %d 道），已全部跳过", skippedDuplicates);
        log.info("知识库题库生成全部重复：{} [kbId={}, difficulty={}]", message, kb.getId(), difficulty);
        return new KnowledgeBaseQuestionGenerationResult(List.of(), skippedDuplicates, message);
      }
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "知识库题库生成结果无有效题干");
    }
    if (skippedDuplicates > 0) {
      log.info("知识库题库生成去重：生成 {} 道，跳过 {} 道重复题，最终保存 {} 道 [kbId={}, difficulty={}]",
          generated.questions().size(), skippedDuplicates, entities.size(),
          kb.getId(), difficulty);
    }
    List<KnowledgeBaseQuestionDTO> saved = questionRepository.saveAll(entities).stream()
        .map(this::toDTO)
        .toList();
    String message = skippedDuplicates > 0
        ? String.format("已新增 %d 道题，跳过 %d 道重复题", saved.size(), skippedDuplicates)
        : String.format("已新增 %d 道题", saved.size());
    return new KnowledgeBaseQuestionGenerationResult(saved, skippedDuplicates, message);
  }

  private void applyCreateRequest(KnowledgeBaseQuestionEntity question,
                                  CreateKnowledgeBaseQuestionRequest request) {
    question.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    question.setDifficulty(normalizeDifficulty(request.difficulty()));
    question.setType(trimToNull(request.type()));
    question.setCategory(normalizeCategory(request.category(), null));
    question.setQuestion(request.question().trim());
    question.setTopicSummary(trimToNull(request.topicSummary()));
    question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
    question.setKeyPointsJson(writeStringList(request.keyPoints()));
    question.setScoringRubric(trimToNull(request.scoringRubric()));
    question.setFollowUpsJson(writeFollowUps(request.followUps()));
    question.setSourceContext(trimToNull(request.sourceContext()));
  }

  private KnowledgeBaseQuestionDTO toDTO(KnowledgeBaseQuestionEntity question) {
    // FK 列声明为 insertable=false/updatable=false，save 后不一定回填；
    // 优先取关联实体的 ID（saveAll 后 KnowledgeBase 已在持久态），FK 列只做兜底
    Long knowledgeBaseId = question.getKnowledgeBase() != null && question.getKnowledgeBase().getId() != null
        ? question.getKnowledgeBase().getId()
        : question.getKnowledgeBaseId();
    return new KnowledgeBaseQuestionDTO(
        question.getId(),
        knowledgeBaseId,
        question.getKnowledgeBase() != null ? question.getKnowledgeBase().getName() : null,
        question.getSkillId(),
        question.getDifficulty(),
        question.getType(),
        question.getCategory(),
        question.getQuestion(),
        question.getTopicSummary(),
        question.getReferenceAnswer(),
        readStringList(question.getKeyPointsJson()),
        question.getScoringRubric(),
        readFollowUps(question.getFollowUpsJson()),
        question.getSourceContext(),
        question.getStatus(),
        question.getCreatedAt(),
        question.getUpdatedAt()
    );
  }

  private String buildGenerationContext(KnowledgeBaseEntity kb) {
    List<Document> docs = new ArrayList<>();
    Set<String> seenTexts = new LinkedHashSet<>();
    for (String query : buildGenerationQueries(kb.getName())) {
      List<Document> hits = vectorService.similaritySearch(
          query, List.of(kb.getId()), RETRIEVAL_QUERY_TOP_K, 0);
      log.info("知识库题目生成检索: kbId={}, query={}, topK={}, minScore=0, hits={}",
          kb.getId(), query, RETRIEVAL_QUERY_TOP_K, hits.size());
      for (Document doc : hits) {
        String text = doc.getText();
        if (text == null || text.isBlank() || !seenTexts.add(text.trim())) {
          continue;
        }
        docs.add(doc);
        if (docs.size() >= RETRIEVAL_TOP_K) {
          break;
        }
      }
      if (docs.size() >= RETRIEVAL_TOP_K) {
        break;
      }
    }
    if (docs.isEmpty()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
          "知识库未检索到可用于生成题目的内容");
    }
    log.info("知识库题目生成上下文: kbId={}, chunks={}", kb.getId(), docs.size());
    String context = docs.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n\n---\n\n"));
    return context.length() <= MAX_CONTEXT_CHARS
        ? context
        : context.substring(0, MAX_CONTEXT_CHARS) + "\n...(知识库片段过长，已截断)";
  }

  private List<String> buildGenerationQueries(String knowledgeBaseName) {
    // 不在 query 里拼接知识库名：检索已经按 kbId 过滤了范围，
    // 拼上书名/标题反而会让向量命中偏向前言、版本记录、目录等元内容页，
    // 挤占真正的技术内容。保留入参签名不动，避免改动面扩大。
    return List.of(
        "核心概念 定义 背景 原理",
        "关键流程 步骤 方法 工作机制",
        "规则约束 条件 边界 例外 限制",
        "典型案例 常见问题 应用场景 最佳实践"
    );
  }

  private String buildExistingCategorySection(Long knowledgeBaseId) {
    List<CategoryCount> categories = questionRepository.findCategoryCounts(knowledgeBaseId);
    if (categories.isEmpty()) {
      return "暂无已有方向";
    }
    return categories.stream()
        .limit(10)
        .map(c -> "- " + c.getCategory() + "（" + c.getCount() + " 题）")
        .collect(Collectors.joining("\n"));
  }

  private String buildExistingQuestionSection(Long knowledgeBaseId, String difficulty) {
    List<String> questions = questionRepository
        .findTop20ByKnowledgeBase_IdAndDifficultyOrderByUpdatedAtDesc(knowledgeBaseId, difficulty)
        .stream()
        .map(KnowledgeBaseQuestionEntity::getQuestion)
        .filter(question -> question != null && !question.isBlank())
        .map(question -> "- " + question.trim())
        .toList();
    return questions.isEmpty() ? "暂无已有题目" : String.join("\n", questions);
  }

  private PromptTemplate loadTemplate(Resource resource) throws IOException {
    try (var input = resource.getInputStream()) {
      String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      return new PromptTemplate(content);
    }
  }

  private KnowledgeBaseEntity getKnowledgeBase(Long knowledgeBaseId) {
    return knowledgeBaseRepository.findById(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
  }

  private KnowledgeBaseQuestionEntity getQuestion(Long questionId) {
    return questionRepository.findById(questionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND));
  }

  private boolean containsKeyword(KnowledgeBaseQuestionEntity question, String keyword) {
    String lower = keyword.toLowerCase(Locale.ROOT);
    return contains(question.getQuestion(), lower)
        || contains(question.getReferenceAnswer(), lower)
        || contains(question.getScoringRubric(), lower)
        || contains(question.getTopicSummary(), lower)
        || contains(question.getCategory(), lower);
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
  }

  private List<String> readStringList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, STRING_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析题目字符串列表失败: {}", e.getMessage());
      return List.of();
    }
  }

  private List<KnowledgeBaseQuestionFollowUpDTO> readFollowUps(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析追问列表失败: {}", e.getMessage());
      return List.of();
    }
  }

  private String writeStringList(List<String> values) {
    try {
      List<String> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && !value.isBlank())
          .map(String::trim)
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表字段失败", e);
    }
  }

  private String writeFollowUps(List<KnowledgeBaseQuestionFollowUpDTO> values) {
    try {
      List<KnowledgeBaseQuestionFollowUpDTO> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && value.question() != null && !value.question().isBlank())
          .map(value -> new KnowledgeBaseQuestionFollowUpDTO(
              value.question().trim(),
              trimToNull(value.referenceAnswer()),
              value.keyPoints() == null ? List.of() : value.keyPoints().stream()
                  .filter(item -> item != null && !item.isBlank())
                  .map(String::trim)
                  .toList(),
              trimToNull(value.scoringRubric())
          ))
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e);
    }
  }

  private String normalizeDifficulty(String difficulty) {
    if (difficulty == null || difficulty.isBlank()) {
      return InterviewDefaults.DIFFICULTY;
    }
    return difficulty.trim();
  }

  /**
   * 归一化方向名：模型输出兜底。
   * - 空白时用知识库名（生成场景）或"未分类"（手动创建场景）
   * - 否则去掉首尾空白
   */
  private String normalizeCategory(String category, String fallback) {
    if (category == null || category.isBlank()) {
      return fallback != null && !fallback.isBlank() ? fallback.trim() : "未分类";
    }
    return category.trim();
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /**
   * 题干归一化：用于跨批次去重比对。
   * 规则：Unicode NFC 规范化 → 转小写 → 去标点和空白。
   * 不做语义相似度去重，避免误伤表达相近但考察点不同的题目。
   */
  private String normalizeQuestionKey(String question) {
    if (question == null) {
      return "";
    }
    String normalized = Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i += 1) {
      char ch = normalized.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        sb.append(ch);
      }
    }
    return sb.toString();
  }
}
