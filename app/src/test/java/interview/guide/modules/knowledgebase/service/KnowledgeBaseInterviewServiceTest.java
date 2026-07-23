package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseInterviewServiceTest {

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  @Mock
  private KnowledgeBaseQuestionRepository questionRepository;

  @Mock
  private InterviewSessionService interviewSessionService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("启用题目不足时拒绝创建知识库面试")
  void shouldRejectWhenActiveQuestionsAreInsufficient() {
    KnowledgeBaseInterviewService service = newService();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of());

    CreateKnowledgeBaseInterviewRequest request =
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 0, "");

    assertThatThrownBy(() -> service.createSession(request))
        .isInstanceOf(interview.guide.common.exception.BusinessException.class)
        .hasMessageContaining("当前启用题目不足");
  }

  @Test
  @DisplayName("不传 category 时跨全部方向抽题")
  @SuppressWarnings("unchecked")
  void shouldCreateSessionAcrossAllCategoriesWhenCategoryIsNull() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    InterviewSessionDTO expected =
        new InterviewSessionDTO("session1", "", 2, 0, List.of(), SessionStatus.CREATED, 1L, null);
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(expected);

    InterviewSessionDTO actual = service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 1, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));
    assertThat(actual).isSameAs(expected);
    assertThat(captor.getValue()).hasSize(2);
    assertThat(captor.getValue().get(0).isFollowUp()).isFalse();
    assertThat(captor.getValue().get(1).isFollowUp()).isTrue();
    assertThat(captor.getValue().get(0).category()).isEqualTo("Redis");
  }

  @Test
  @DisplayName("传 category 时按方向过滤候选题")
  @SuppressWarnings("unchecked")
  void shouldFilterByCategoryWhenCategoryProvided() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "Redis", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("Redis")))
        .thenReturn(new InterviewSessionDTO("s", "", 1, 0, List.of(), SessionStatus.CREATED, 1L, "Redis"));

    service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, "Redis", "mid", 1, 0, ""));

    // 验证走的是按 category 过滤的查询方法，而不是全量方法
    verify(questionRepository).findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "Redis", KnowledgeBaseQuestionStatus.ACTIVE);
  }

  @Test
  @DisplayName("创建知识库面试时将 category 规范化后传递到面试会话")
  @SuppressWarnings("unchecked")
  void shouldPassNormalizedCategoryToSession() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
        1L, "mid", "MySQL", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("MySQL")))
        .thenReturn(new InterviewSessionDTO("s", "", 1, 0, List.of(), SessionStatus.CREATED, 1L, "MySQL"));

    InterviewSessionDTO actual = service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, "  MySQL  ", "mid", 1, 0, ""));

    verify(interviewSessionService).createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq("MySQL"));
    assertThat(actual.interviewCategory()).isEqualTo("MySQL");
  }

  @Test
  @DisplayName("followUpCount 小于追问池时随机抽取指定数量的追问")
  @SuppressWarnings("unchecked")
  void shouldPickFollowUpsRandomlyWhenPoolLargerThanCount() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithThreeFollowUps();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(new InterviewSessionDTO("s", "", 3, 0, List.of(), SessionStatus.CREATED, 1L, null));

    service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 2, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));

    List<InterviewQuestionDTO> built = captor.getValue();
    assertThat(built).hasSize(3);
    List<String> followUpQuestions = built.subList(1, 3).stream()
        .map(InterviewQuestionDTO::question).toList();
    assertThat(followUpQuestions).hasSize(2);
    assertThat(followUpQuestions).doesNotHaveDuplicates();
    assertThat(followUpQuestions).isSubsetOf("追问1", "追问2", "追问3");
  }

  @Test
  @DisplayName("followUpCount 大于追问池时返回全部追问而不报错")
  @SuppressWarnings("unchecked")
  void shouldReturnAllFollowUpsWhenCountExceedsPool() throws Exception {
    KnowledgeBaseInterviewService service = newService();
    KnowledgeBaseQuestionEntity question = questionWithFollowUp();
    when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBaseEntity()));
    when(questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
        1L, "mid", KnowledgeBaseQuestionStatus.ACTIVE)).thenReturn(List.of(question));
    when(interviewSessionService.createSessionFromQuestions(
        any(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L), eq(null)))
        .thenReturn(new InterviewSessionDTO("s", "", 2, 0, List.of(), SessionStatus.CREATED, 1L, null));

    service.createSession(
        new CreateKnowledgeBaseInterviewRequest(1L, null, "mid", 1, 3, ""));

    ArgumentCaptor<List<InterviewQuestionDTO>> captor = ArgumentCaptor.forClass(List.class);
    verify(interviewSessionService).createSessionFromQuestions(
        captor.capture(), eq(""), eq(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID), eq("mid"), eq(1L),
        eq(null));
    assertThat(captor.getValue()).hasSize(2);
  }

  private KnowledgeBaseInterviewService newService() {
    return new KnowledgeBaseInterviewService(
        knowledgeBaseRepository,
        questionRepository,
        interviewSessionService,
        objectMapper
    );
  }

  private KnowledgeBaseQuestionEntity questionWithFollowUp() throws Exception {
    KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
    entity.setQuestion("主问题");
    entity.setType("REDIS");
    entity.setCategory("Redis");
    entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    entity.setDifficulty("mid");
    entity.setReferenceAnswer("参考答案");
    entity.setKeyPointsJson(objectMapper.writeValueAsString(List.of("要点")));
    entity.setScoringRubric("评分规则");
    entity.setFollowUpsJson(objectMapper.writeValueAsString(List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问", "追问参考答案", List.of("追问要点"), "追问评分规则")
    )));
    return entity;
  }

  private KnowledgeBaseQuestionEntity questionWithThreeFollowUps() throws Exception {
    KnowledgeBaseQuestionEntity entity = questionWithFollowUp();
    entity.setFollowUpsJson(objectMapper.writeValueAsString(List.of(
        new KnowledgeBaseQuestionFollowUpDTO("追问1", "答1", List.of(), "规则1"),
        new KnowledgeBaseQuestionFollowUpDTO("追问2", "答2", List.of(), "规则2"),
        new KnowledgeBaseQuestionFollowUpDTO("追问3", "答3", List.of(), "规则3")
    )));
    return entity;
  }
}
