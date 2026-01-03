package interview.guide.modules.knowledgebase;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.*;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;

    /**
     * 上传知识库文件
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(uploadService.uploadKnowledgeBase(file, name, category));
    }

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy) {
        if (sortBy != null && !sortBy.isBlank()) {
            return Result.success(listService.listSorted(sortBy));
        }
        return Result.success(listService.listKnowledgeBases());
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return listService.getKnowledgeBase(id)
            .map(Result::success)
            .orElse(Result.error("知识库不存在"));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success(null);
    }

    /**
     * 基于知识库回答问题（支持多知识库）
     */
    @PostMapping("/api/knowledgebase/query")
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    /**
     * 基于知识库回答问题（流式SSE，支持多知识库）
     */
    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        log.info("收到知识库流式查询请求: kbIds={}, question={}", request.knowledgeBaseIds(), request.question());
        return queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question());
    }

    // ========== 分类管理 API ==========

    /**
     * 获取所有分类
     */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    /**
     * 根据分类获取知识库列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(listService.listByCategory(category));
    }

    /**
     * 获取未分类的知识库
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(listService.listByCategory(null));
    }

    /**
     * 更新知识库分类
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        listService.updateCategory(id, body.get("category"));
        return Result.success(null);
    }

    // ========== 搜索 API ==========

    /**
     * 搜索知识库
     */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(listService.search(keyword));
    }

    // ========== 统计 API ==========

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    // ========== 向量化管理 API ==========

    /**
     * 重新向量化知识库（手动重试）
     * 用于向量化失败后的重试
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    public Result<Void> revectorize(@PathVariable Long id) {
        uploadService.revectorize(id);
        return Result.success(null);
    }
}

