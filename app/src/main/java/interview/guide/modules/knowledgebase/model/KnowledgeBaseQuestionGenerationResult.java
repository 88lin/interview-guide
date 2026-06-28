package interview.guide.modules.knowledgebase.model;

import java.util.List;

public record KnowledgeBaseQuestionGenerationResult(
    List<KnowledgeBaseQuestionDTO> saved,
    int skipped,
    String message
) {
}
