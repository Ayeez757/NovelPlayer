package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.generation.GenerationStageResult;
import com.novelplayer.domain.generation.GenerationStatus;
import com.novelplayer.infra.repository.GenerationStageResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 生成阶段结果的统一存取入口。
 *
 * <p>后续章节摘要、故事圣经、场景规划和分场草稿都会通过这里落库，避免各阶段重复实现
 * JSON 序列化、输入哈希、缓存命中和失败记录逻辑。</p>
 */
@Component
public class GenerationStageStore {

    private static final Logger log = LoggerFactory.getLogger(GenerationStageStore.class);

    private final GenerationStageResultRepository stageResultRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建阶段结果存取层。
     *
     * @param stageResultRepository 阶段结果仓储。
     * @param objectMapper Spring Boot 统一配置的 JSON 序列化器。
     */
    public GenerationStageStore(GenerationStageResultRepository stageResultRepository, ObjectMapper objectMapper) {
        this.stageResultRepository = stageResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询同一任务、同一阶段、同一输入哈希下最近一次成功结果，并反序列化为指定类型。
     *
     * @param job 生成任务，必须已经持久化。
     * @param stageName 阶段名称。
     * @param inputHash 阶段输入哈希。
     * @param type 输出对象类型。
     * @return 命中的阶段结果；没有成功结果时返回空。
     * @param <T> 阶段输出类型。
     */
    public <T> Optional<T> findSucceeded(GenerationJob job, String stageName, String inputHash, Class<T> type) {
        Long jobId = requireJobId(job);
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = requireHash(inputHash);
        Optional<GenerationStageResult> existing = stageResultRepository
                .findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                        jobId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash);
        if (existing.isPresent()) {
            log.debug("命中已成功的生成阶段结果 jobId={} stageName={} inputHash={} resultId={} targetType={}",
                    jobId, normalizedStageName, normalizedHash, existing.get().getId(), type.getSimpleName());
        } else {
            log.debug("未命中可复用的生成阶段结果 jobId={} stageName={} inputHash={} targetType={}",
                    jobId, normalizedStageName, normalizedHash, type.getSimpleName());
        }
        return existing.map(result -> readValue(result.getOutputJson(), type));
    }

    /**
     * 判断同一任务、同一阶段、同一输入哈希下是否已有成功结果。
     *
     * @param job 生成任务，必须已经持久化。
     * @param stageName 阶段名称。
     * @param inputHash 阶段输入哈希。
     * @return 已有成功结果时返回 true。
     */
    public boolean hasSucceeded(GenerationJob job, String stageName, String inputHash) {
        Long jobId = requireJobId(job);
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = requireHash(inputHash);
        boolean succeeded = stageResultRepository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                jobId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash).isPresent();
        log.debug("检查生成阶段是否可复用 jobId={} stageName={} inputHash={} reusable={}",
                jobId, normalizedStageName, normalizedHash, succeeded);
        return succeeded;
    }

    /**
     * 保存一个成功完成的阶段结果。
     *
     * @param job 生成任务，必须已经持久化。
     * @param stageName 阶段名称。
     * @param inputHash 阶段输入哈希。
     * @param output 阶段输出对象，会序列化为 JSON 保存。
     * @return 原样返回 output，方便调用方链式使用。
     * @param <T> 阶段输出类型。
     */
    public <T> T saveSucceeded(GenerationJob job, String stageName, String inputHash, T output) {
        Long jobId = requireJobId(job);
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = requireHash(inputHash);
        String outputJson = writeValue(output);
        stageResultRepository.save(new GenerationStageResult(
                job,
                normalizedStageName,
                GenerationStatus.SUCCEEDED,
                normalizedHash,
                outputJson,
                null
        ));
        log.info("生成阶段结果保存成功 jobId={} stageName={} inputHash={} outputLength={}",
                jobId, normalizedStageName, normalizedHash, outputJson.length());
        return output;
    }

    /**
     * 保存一个失败的阶段结果。
     *
     * <p>失败结果允许没有 inputHash，因为有些失败可能发生在输入哈希生成前。</p>
     *
     * @param job 生成任务，必须已经持久化。
     * @param stageName 阶段名称。
     * @param inputHash 阶段输入哈希，可为空。
     * @param errorMessage 失败原因。
     */
    public void saveFailed(GenerationJob job, String stageName, @Nullable String inputHash, String errorMessage) {
        Long jobId = requireJobId(job);
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = normalizeHash(inputHash);
        String normalizedErrorMessage = normalizeErrorMessage(errorMessage);
        stageResultRepository.save(new GenerationStageResult(
                job,
                normalizedStageName,
                GenerationStatus.FAILED,
                normalizedHash,
                null,
                normalizedErrorMessage
        ));
        log.warn("生成阶段结果记录为失败 jobId={} stageName={} inputHash={} errorMessage={}",
                jobId, normalizedStageName, normalizedHash, normalizedErrorMessage);
    }

    /**
     * 计算普通字符串的 SHA-256 摘要。
     *
     * @param value 待计算摘要的字符串。
     * @return 十六进制格式的 SHA-256 摘要。
     */
    public String sha256Of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            String result = HexFormat.of().formatHex(hash);
            log.debug("阶段输入哈希计算完成 valueLength={} hash={}", value.length(), result);
            return result;
        } catch (NoSuchAlgorithmException exception) {
            log.error("计算阶段输入哈希失败，当前运行环境缺少 SHA-256 算法", exception);
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    /**
     * 将对象序列化为 JSON 后计算 SHA-256 摘要。
     *
     * @param value 待计算摘要的对象。
     * @return 对象 JSON 表示的 SHA-256 摘要。
     */
    public String sha256OfJson(Object value) {
        return sha256Of(writeValue(value));
    }

    /**
     * 将阶段输出对象序列化为 JSON。
     *
     * @param value 阶段输出对象。
     * @return JSON 字符串。
     */
    private String writeValue(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            log.debug("生成阶段载荷序列化完成 payloadType={} jsonLength={}",
                    value == null ? "null" : value.getClass().getSimpleName(), json.length());
            return json;
        } catch (Exception exception) {
            log.warn("生成阶段载荷序列化失败 payloadType={}",
                    value == null ? "null" : value.getClass().getName(), exception);
            throw new IllegalStateException("Failed to serialize stage payload", exception);
        }
    }

    /**
     * 将阶段输出 JSON 反序列化为指定类型。
     *
     * @param json 阶段输出 JSON。
     * @param type 目标类型。
     * @return 反序列化后的对象。
     * @param <T> 阶段输出类型。
     */
    private <T> T readValue(String json, Class<T> type) {
        try {
            T value = objectMapper.readValue(json, type);
            log.debug("生成阶段载荷反序列化完成 targetType={} jsonLength={}",
                    type.getSimpleName(), json == null ? 0 : json.length());
            return value;
        } catch (Exception exception) {
            log.warn("生成阶段载荷反序列化失败 targetType={} jsonLength={}",
                    type.getName(), json == null ? 0 : json.length(), exception);
            throw new IllegalStateException("Failed to deserialize stage payload", exception);
        }
    }

    /**
     * 校验任务已经持久化，并返回任务主键。
     *
     * @param job 生成任务。
     * @return 任务主键。
     */
    private static Long requireJobId(GenerationJob job) {
        if (job == null || job.getId() == null) {
            throw new IllegalArgumentException("job must not be null and must be persisted");
        }
        return job.getId();
    }

    /**
     * 校验并规范化阶段名称。
     *
     * @param stageName 阶段名称。
     * @return 去除首尾空白后的阶段名称。
     */
    private static String requireStageName(String stageName) {
        if (stageName == null || stageName.isBlank()) {
            throw new IllegalArgumentException("stageName must not be blank");
        }
        return stageName.trim();
    }

    /**
     * 校验并规范化非空输入哈希。
     *
     * @param inputHash 输入哈希。
     * @return 去除首尾空白后的输入哈希。
     */
    private static String requireHash(String inputHash) {
        if (inputHash == null || inputHash.isBlank()) {
            throw new IllegalArgumentException("inputHash must not be blank");
        }
        return inputHash.trim();
    }

    /**
     * 规范化可为空的输入哈希。
     *
     * @param inputHash 输入哈希，可为空。
     * @return 去除首尾空白后的输入哈希；空白值返回 null。
     */
    private static String normalizeHash(@Nullable String inputHash) {
        if (inputHash == null) {
            return null;
        }
        String trimmed = inputHash.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 规范化阶段失败原因，避免落库空错误信息。
     *
     * @param errorMessage 原始错误信息。
     * @return 可展示、可排查的错误信息。
     */
    private static String normalizeErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Unknown generation stage failure";
        }
        return errorMessage.trim();
    }
}
