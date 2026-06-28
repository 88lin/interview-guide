package interview.guide.modules.knowledgebase.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseInterviewService {

  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
  };
  private static final TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>> FOLLOW_UP_LIST_TYPE =
      new TypeReference<>() {
      };

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final InterviewSessionService interviewSessionService;
  private final ObjectMapper objectMapper;

  private record QuestionSource(KnowledgeBaseQuestionEntity question,
                                List<String> keyPoints,
                                List<KnowledgeBaseQuestionFollowUpDTO> followUps) {
  }

  public InterviewSessionDTO createSession(CreateKnowledgeBaseInterviewRequest request) {
    knowledgeBaseRepository.findById(request.knowledgeBaseId())
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

    String category = trimToNull(request.category());
    String difficulty = normalizeDifficulty(request.difficulty());
    int mainCount = request.mainQuestionCount();
    int followUpCount = request.followUpCount();

    List<KnowledgeBaseQuestionEntity> raw = selectActiveQuestions(
        request.knowledgeBaseId(), category, difficulty);

    List<QuestionSource> candidates = raw.stream()
        .map(question -> new QuestionSource(
            question,
            readStringList(question.getKeyPointsJson()),
            readFollowUps(question.getFollowUpsJson())
        ))
        // 仅当 followUpCount > 0 时才要求至少有 1 个追问可用，
        // pickFollowUps 会在池容量不足时返回全部追问。
        .filter(source -> followUpCount == 0 || !source.followUps().isEmpty())
        .toList();

    if (candidates.size() < mainCount) {
      throw new BusinessException(
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "当前启用题目不足：需要 " + mainCount + " 道主问题，满足条件的题目只有 "
              + candidates.size() + " 道"
              + (category != null ? "（方向：" + category + "）" : "（全部方向）")
      );
    }

    List<QuestionSource> selected = new ArrayList<>(candidates);
    Collections.shuffle(selected);
    List<InterviewQuestionDTO> questions = buildQuestions(selected.subList(0, mainCount), followUpCount);

    log.info("创建知识库面试: kbId={}, category={}, difficulty={}, mainQuestions={}, totalQuestions={}",
        request.knowledgeBaseId(), category, difficulty, mainCount, questions.size());

    // skillId 已不再有业务含义，统一用默认值回填到面试会话表
    return interviewSessionService.createSessionFromQuestions(
        questions,
        request.llmProvider(),
        KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID,
        difficulty,
        request.knowledgeBaseId()
    );
  }

  /**
   * 按 category 过滤候选题。
   * category 为空时跨所有方向筛选。
   */
  private List<KnowledgeBaseQuestionEntity> selectActiveQuestions(
      Long knowledgeBaseId, String category, String difficulty) {
    if (category == null) {
      return questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
          knowledgeBaseId, difficulty, KnowledgeBaseQuestionStatus.ACTIVE);
    }
    return questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        knowledgeBaseId, difficulty, category, KnowledgeBaseQuestionStatus.ACTIVE);
  }

  private List<InterviewQuestionDTO> buildQuestions(List<QuestionSource> selected, int followUpCount) {
    List<InterviewQuestionDTO> questions = new ArrayList<>();
    for (QuestionSource source : selected) {
      KnowledgeBaseQuestionEntity entity = source.question();
      int mainIndex = questions.size();
      questions.add(InterviewQuestionDTO.fromQuestionBank(
          mainIndex,
          entity.getQuestion(),
          defaultString(entity.getType(), "KNOWLEDGE_BASE"),
          defaultString(entity.getCategory(), "知识库"),
          entity.getTopicSummary(),
          entity.getReferenceAnswer(),
          source.keyPoints(),
          entity.getScoringRubric(),
          entity.getSourceContext()
      ));

      // 在可用的追问池里随机抽 followUpCount 个，避免每次面试都问同一组追问
      List<KnowledgeBaseQuestionFollowUpDTO> picked = pickFollowUps(source.followUps(), followUpCount);
      for (KnowledgeBaseQuestionFollowUpDTO followUp : picked) {
        questions.add(new InterviewQuestionDTO(
            questions.size(),
            followUp.question(),
            defaultString(entity.getType(), "KNOWLEDGE_BASE"),
            defaultString(entity.getCategory(), "知识库追问"),
            entity.getTopicSummary(),
            null,
            null,
            null,
            true,
            mainIndex,
            followUp.referenceAnswer(),
            followUp.keyPoints(),
            followUp.scoringRubric(),
            entity.getSourceContext()
        ));
      }
    }
    return questions;
  }

  /**
   * 在追问池里随机抽取 count 个，池容量不足时返回全部。
   * 使用 Fisher-Yates 局部洗牌，避免改动原列表顺序。
   */
  private List<KnowledgeBaseQuestionFollowUpDTO> pickFollowUps(
      List<KnowledgeBaseQuestionFollowUpDTO> pool, int count) {
    if (pool == null || pool.isEmpty() || count <= 0) {
      return List.of();
    }
    int n = Math.min(count, pool.size());
    if (n == pool.size()) {
      return new ArrayList<>(pool);
    }
    List<KnowledgeBaseQuestionFollowUpDTO> copy = new ArrayList<>(pool);
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < n; i += 1) {
      int j = random.nextInt(i, copy.size());
      KnowledgeBaseQuestionFollowUpDTO tmp = copy.get(i);
      copy.set(i, copy.get(j));
      copy.set(j, tmp);
    }
    return copy.subList(0, n);
  }

  private List<String> readStringList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, STRING_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析题目要点失败: {}", e.getMessage());
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
      log.warn("解析追问失败: {}", e.getMessage());
      return List.of();
    }
  }

  private String normalizeDifficulty(String difficulty) {
    if (difficulty == null || difficulty.isBlank()) {
      return InterviewDefaults.DIFFICULTY;
    }
    return difficulty.trim();
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private String defaultString(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value;
  }
}
