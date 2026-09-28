package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.RagChatController;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

class RagSourcePersistenceTest {
  @Test
  @DisplayName("来源尾注经过现有纯文本 SSE 累积和消息保存后，历史 DTO 原样返回")
  void persistsFooterAndReloadsHistory() {
    var sessions = mock(RagChatSessionRepository.class);
    var messages = mock(RagChatMessageRepository.class);
    var kbMapper = mock(KnowledgeBaseMapper.class);
    var service = spy(new RagChatSessionService(sessions, messages, mock(KnowledgeBaseRepository.class),
        mock(KnowledgeBaseQueryService.class), Mappers.getMapper(RagChatMapper.class), kbMapper,
        new KnowledgeBaseQueryProperties()));
    var session = new RagChatSessionEntity();
    session.setId(1L);
    var message = new RagChatMessageEntity();
    message.setId(2L);
    message.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    when(messages.findById(2L)).thenReturn(Optional.of(message));
    when(sessions.findByIdWithKnowledgeBases(1L)).thenReturn(Optional.of(session));
    when(messages.findBySessionIdOrderByMessageOrderAsc(1L)).thenReturn(List.of(message));
    when(kbMapper.toListItemDTOList(List.of())).thenReturn(List.of());
    String footer = RagSources.footer(List.of(new Document("正文", Map.of("source_name", "手册", "kb_id", "9"))));
    doReturn(2L).when(service).prepareStreamMessage(1L, "问题");
    doReturn(Flux.just("回答", footer)).when(service).getStreamAnswer(1L, "问题");

    var events = new RagChatController(service).sendMessageStream(1L, new SendMessageRequest("问题"))
        .collectList().block();

    verify(messages).save(message);
    assertThat(message.getCompleted()).isTrue();
    assertThat(message.getContent()).isEqualTo("回答" + footer);
    assertThat(events).hasSize(2);
    assertThat(events.getLast().data()).isEqualTo(footer.replace("\n", "\\n"));
    assertThat(service.getSessionDetail(1L).messages().getFirst().content()).isEqualTo("回答" + footer);
  }
}
