package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 简历上传服务
 * 处理简历上传、解析的业务逻辑
 * AI 分析改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final RedisService redisService;
    private final ResumeRepository resumeRepository;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件
     * @return 上传结果（分析将异步进行）
     */
    public Map<String, Object> uploadAndAnalyze(org.springframework.web.multipart.MultipartFile file) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        log.info("收到简历上传请求: {}, 大小: {} bytes", fileName, file.getSize());

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        // 3. 检查简历是否已存在（去重）
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            return handleDuplicateResume(existingResume.get());
        }

        // 4. 解析简历文本
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }

        // 5. 保存简历到RustFS
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {}", fileKey);

        // 6. 保存简历到数据库（状态为 PENDING）
        ResumeEntity savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);

        // 7. 发送分析任务到 Redis Stream（异步处理）
        sendAnalyzeTask(savedResume.getId(), resumeText);

        log.info("简历上传完成，分析任务已入队: {}, resumeId={}", fileName, savedResume.getId());

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
            "resume", Map.of(
                "id", savedResume.getId(),
                "filename", savedResume.getOriginalFilename(),
                "analyzeStatus", AsyncTaskStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
            "duplicate", false
        );
    }

    /**
     * 发送分析任务到 Redis Stream
     *
     * @param resumeId 简历ID
     * @param content  简历文本内容
     */
    private void sendAnalyzeTask(Long resumeId, String content) {
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, content,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
            );

            String messageId = redisService.streamAdd(
                AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                message
            );

            log.info("分析任务已发送到Stream: resumeId={}, messageId={}", resumeId, messageId);
        } catch (Exception e) {
            log.error("发送分析任务失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            // 发送失败时更新状态为 FAILED
            updateAnalyzeStatus(resumeId, AsyncTaskStatus.FAILED, "任务入队失败: " + e.getMessage());
        }
    }

    /**
     * 更新分析状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        resumeRepository.findById(resumeId).ifPresent(resume -> {
            resume.setAnalyzeStatus(status);
            if (error != null) {
                resume.setAnalyzeError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            resumeRepository.save(resume);
        });
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.getAllowedTypes(),
            "不支持的文件类型: " + contentType
        );
    }

    /**
     * 处理重复简历
     */
    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.getId());

        // 获取历史分析结果
        Optional<ResumeAnalysisResponse> analysisOpt = persistenceService.getLatestAnalysisAsDTO(resume.getId());

        if (analysisOpt.isPresent()) {
            // 已有分析结果，直接返回
            return Map.of(
                "analysis", analysisOpt.get(),
                "storage", Map.of(
                    "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                    "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                    "resumeId", resume.getId()
                ),
                "duplicate", true
            );
        } else {
            // 没有分析结果（可能之前分析失败），返回当前状态
            return Map.of(
                "resume", Map.of(
                    "id", resume.getId(),
                    "filename", resume.getOriginalFilename(),
                    "analyzeStatus", resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus().name() : AsyncTaskStatus.PENDING.name()
                ),
                "storage", Map.of(
                    "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                    "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                    "resumeId", resume.getId()
                ),
                "duplicate", true
            );
        }
    }

    /**
     * 重新分析简历（手动重试）
     * 从数据库获取简历文本并发送分析任务
     *
     * @param resumeId 简历ID
     */
    @Transactional
    public void reanalyze(Long resumeId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, resume.getOriginalFilename());

        String resumeText = resume.getResumeText();
        if (resumeText == null || resumeText.trim().isEmpty()) {
            // 如果没有缓存的文本，尝试重新解析
            resumeText = parseService.downloadAndParseContent(resume.getStorageKey(), resume.getOriginalFilename());
            if (resumeText == null || resumeText.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
            // 更新缓存的文本
            resume.setResumeText(resumeText);
        }

        // 更新状态为 PENDING
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resumeRepository.save(resume);

        // 发送分析任务到 Stream
        sendAnalyzeTask(resumeId, resumeText);

        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }
}
