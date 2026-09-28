package interview.guide.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RagSourcesTest {
  @Test
  @DisplayName("编号与最终排序一致，同名来源保留知识库标识，不伪造页码")
  void orderedSources() {
    List<Document> docs = List.of(
        new Document("第一份正文", Map.of("source_name", "手册", "source_filename", "java.md",
            "kb_id", "2", "chunk_index", 3, "total_chunks", 20)),
        new Document("第二份正文", Map.of("source_name", "手册", "kb_id", "1")));
    assertThat(RagSources.context(docs)).containsSubsequence("[S1]", "第一份正文", "[S2]", "第二份正文");
    assertThat(RagSources.footer(docs)).containsSubsequence("[S1]", "知识库 2", "第 4/20", "[S2]", "知识库 1")
        .doesNotContain("页", "[S3]");
  }

  @Test
  @DisplayName("来源名中的 Markdown、HTML 和换行不会注入链接或新段落")
  void escapesMetadata() {
    String footer = RagSources.footer(List.of(new Document("正文", Map.of(
        "source_name", "[恶意](https://example.com)\n# 标题", "source_filename", "<script>`x`&lt;.md"))));
    assertThat(footer).contains("\\[恶意\\]\\(https://example\\.com\\)", "\\<script\\>", "\\`x\\`", "\\&lt;")
        .doesNotContain("\n# 标题", "<script>");
  }

  @Test
  @DisplayName("旧向量缺少来源信息时不编造文件名或片段序号，空检索无尾注")
  void legacySources() {
    assertThat(RagSources.footer(List.of())).isEmpty();
    assertThat(RagSources.footer(List.of(new Document("旧数据"))))
        .contains("知识库片段").doesNotContain("原文件", "第 ");
  }
}
