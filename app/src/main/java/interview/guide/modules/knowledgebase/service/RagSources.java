package interview.guide.modules.knowledgebase.service;

import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/** 对实际进入 Prompt 的检索片段编号，来源尾注由服务端生成。 */
final class RagSources {
  private RagSources() {}

  static String context(List<Document> documents) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < documents.size(); i++) {
      if (i > 0) {
        result.append("\n\n---\n\n");
      }
      result.append("[S").append(i + 1).append("] 来源：")
          .append(description(documents.get(i))).append("\n")
          .append(documents.get(i).getText());
    }
    return result.toString();
  }

  static String footer(List<Document> documents) {
    if (documents.isEmpty()) {
      return "";
    }
    StringBuilder result = new StringBuilder("\n\n---\n\n参考片段（本次检索提供）：\n\n");
    for (int i = 0; i < documents.size(); i++) {
      result.append("- [S").append(i + 1).append("] ")
          .append(description(documents.get(i))).append("\n");
    }
    return result.toString();
  }

  private static String description(Document document) {
    Map<String, Object> metadata = document.getMetadata();
    String name = text(metadata.get("source_name"));
    String kbId = text(metadata.get("kb_id"));
    StringBuilder result = new StringBuilder(name.isBlank() ? "知识库片段" : name);
    if (!kbId.isBlank()) {
      result.append("（知识库 ").append(kbId).append("）");
    }
    String filename = text(metadata.get("source_filename"));
    if (!filename.isBlank()) {
      result.append("，原文件：").append(filename);
    }
    Object index = metadata.get("chunk_index");
    Object total = metadata.get("total_chunks");
    if (index instanceof Number n && total instanceof Number count
        && n.intValue() >= 0 && n.intValue() < count.intValue()) {
      result.append("，第 ").append(n.intValue() + 1).append("/")
          .append(count.intValue()).append(" 个片段");
    }
    return result.toString();
  }

  private static String text(Object value) {
    if (value == null) {
      return "";
    }
    StringBuilder escaped = new StringBuilder();
    String singleLine = value.toString().replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").trim();
    for (char c : singleLine.toCharArray()) {
      if ("\\`*_{}[]()#+-.!|<>~&".indexOf(c) >= 0) {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    return escaped.toString();
  }
}
