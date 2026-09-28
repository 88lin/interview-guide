package interview.guide.common.async;

import lombok.Data;

import java.time.Duration;

/**
 * 卡住任务恢复调度配置（P1-07）。两个前缀共用同一结构：
 * {@code app.async.recovery.vectorize} 与 {@code app.async.recovery.resume-analyze}。
 *
 * <p>注意：pending 阈值必须大于正常排队等待时间（消费者单线程，多个大文件排队
 * 十分钟以上是正常现象）。processing 阈值需要覆盖两次业务心跳之间的最长耗时，
 * 包括 SDK 和业务层重试；LLM 的 5 分钟读超时不是整次业务调用的截止时间。
 * 若累计调用超过阈值，Scheduler 可能恢复仍在执行的任务，attemptId 只能阻止旧结果落库。
 */
@Data
public abstract class AsyncTaskRecoveryProperties {

    /**
     * 恢复调度总开关；关闭后不影响手动重试。
     */
    private boolean enabled = true;

    /**
     * PENDING 超过该时长视为投递丢失，重新补投。默认 10 分钟起。
     */
    private Duration pendingThreshold = Duration.ofMinutes(10);

    /**
     * PROCESSING 超过该时长且期间无心跳进展，条件重置回 PENDING 再补投。
     */
    private Duration processingThreshold = Duration.ofMinutes(15);

    /**
     * Embedding/LLM 阶段心跳节流间隔。
     */
    private Duration heartbeatThrottle = Duration.ofSeconds(30);

    /**
     * 调度轮询间隔（毫秒，供 @Scheduled fixedDelayString 使用）。
     */
    private long intervalMs = 60_000;

    /**
     * 每轮对每种状态分别扫描的任务数量上限；PENDING 和 PROCESSING 各最多取此数量。
     */
    private int batchSize = 10;

    /**
     * 自动恢复次数上限，超过后转 FAILED，不再循环补投。
     */
    private int maxRecoveryCount = 3;
}
