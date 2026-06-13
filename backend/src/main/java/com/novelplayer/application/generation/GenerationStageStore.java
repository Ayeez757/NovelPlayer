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
 * 为分阶段生成流水线提供统一的阶段结果存取能力
 */
@Component
public class GenerationStageStore {

    private static final Logger log = LoggerFactory.getLogger(GenerationStageStore.class);

    private final GenerationStageResultRepository stageResultRepository;
    private final ObjectMapper objectMapper;

    public GenerationStageStore(GenerationStageResultRepository stageResultRepository, ObjectMapper objectMapper) {
        this.stageResultRepository = stageResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询当前任务下执行成功的阶段结果记录
     *
     * 当前策略优先按项目维度复用数据：有项目id，说明新建任务可复用同一项目下过往任务已成功产出的阶段结果。
     * 若单元测试构建临时项目、无持久化项目ID时，则降级为旧逻辑，根据任务ID进行查询匹配。
     */
    public <T> Optional<T> findSucceeded(GenerationJob job, String stageName, String inputHash, Class<T> type) {
        /*
         * Long jobId = requireJobId(job);
         * Optional<GenerationStageResult> existing = stageResultRepository
         *         .findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
         *                 jobId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash);
         */
        Long projectId = extractProjectId(job);
        if (projectId != null) {
            return findSucceeded(projectId, stageName, inputHash, type);
        }
        return findSucceededByJobId(requireJobId(job), stageName, inputHash, type);
    }

    /**
     * 根据项目查询已执行成功的阶段结果记录
     */
    public <T> Optional<T> findSucceeded(Long projectId, String stageName, String inputHash, Class<T> type) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = requireHash(inputHash);
        Optional<GenerationStageResult> existing = stageResultRepository
                .findFirstByJobProjectIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                        projectId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash);
        logLookupResult(existing, "projectId", projectId, normalizedStageName, normalizedHash, type);
        return existing.map(result -> readValue(result.getOutputJson(), type));
    }

    private <T> Optional<T> findSucceededByJobId(Long jobId, String stageName, String inputHash, Class<T> type) {
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = requireHash(inputHash);
        Optional<GenerationStageResult> existing = stageResultRepository
                .findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                        jobId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash);
        logLookupResult(existing, "jobId", jobId, normalizedStageName, normalizedHash, type);
        return existing.map(result -> readValue(result.getOutputJson(), type));
    }

    /**
     * 检查当前任务是否已存在执行成功的阶段结果记录
     */
    public boolean hasSucceeded(GenerationJob job, String stageName, String inputHash) {
        /*
         * Long jobId = requireJobId(job);
         * boolean succeeded = stageResultRepository
         *         .findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
         *                 jobId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash)
         *         .isPresent();
         */

        Long projectId = extractProjectId(job);
        if (projectId != null) {
            return hasSucceeded(projectId, stageName, inputHash);
        }
        return hasSucceededByJobId(requireJobId(job), stageName, inputHash);
    }

    /**
     * 按项目维度校验是否已存在执行成功的阶段结果记录
     */
    public boolean hasSucceeded(Long projectId, String stageName, String inputHash) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = requireHash(inputHash);
        boolean succeeded = stageResultRepository
                .findFirstByJobProjectIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                        projectId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash)
                .isPresent();
        log.debug("Stage reuse check projectId={} stageName={} inputHash={} reusable={}",
                projectId, normalizedStageName, normalizedHash, succeeded);
        return succeeded;
    }

    private boolean hasSucceededByJobId(Long jobId, String stageName, String inputHash) {
        String normalizedStageName = requireStageName(stageName);
        String normalizedHash = requireHash(inputHash);
        boolean succeeded = stageResultRepository
                .findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                        jobId, normalizedStageName, GenerationStatus.SUCCEEDED, normalizedHash)
                .isPresent();
        log.debug("Stage reuse check jobId={} stageName={} inputHash={} reusable={}",
                jobId, normalizedStageName, normalizedHash, succeeded);
        return succeeded;
    }

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
        log.info("阶段结果已保存。 jobId={} stageName={} inputHash={} outputLength={}",
                jobId, normalizedStageName, normalizedHash, outputJson.length());
        return output;
    }

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
        log.warn("阶段结果保存失败。 jobId={} stageName={} inputHash={} errorMessage={}",
                jobId, normalizedStageName, normalizedHash, normalizedErrorMessage);
    }
    // 哈希工具：生成输入指纹（防重复 AI 调用）
    public String sha256Of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            String result = HexFormat.of().formatHex(hash);
            log.debug("Stage input hash generated valueLength={} hash={}", value.length(), result);
            return result;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    public String sha256OfJson(Object value) {
        return sha256Of(writeValue(value));
    }

// JSON 序列化 / 反序列化私有工具
    private String writeValue(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            log.debug("Stage payload serialized payloadType={} jsonLength={}",
                    value == null ? "null" : value.getClass().getSimpleName(), json.length());
            return json;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize stage payload", exception);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            T value = objectMapper.readValue(json, type);
            log.debug("Stage payload deserialized targetType={} jsonLength={}",
                    type.getSimpleName(), json == null ? 0 : json.length());
            return value;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to deserialize stage payload", exception);
        }
    }
    // job 必须有数据库主键 ID，临时未持久化 job 直接抛错；
    private static Long requireJobId(GenerationJob job) {
        if (job == null || job.getId() == null) {
            throw new IllegalArgumentException("job must not be null and must be persisted");
        }
        return job.getId();
    }

    // 有值时提取项目 ID；测试场景中可能仅手动设置任务 ID，故允许项目 ID 为空。
    private static Long extractProjectId(GenerationJob job) {
        requireJobId(job);
        if (job.getProject() == null) {
            return null;
        }
        return job.getProject().getId();
    }

    // 统一打印缓存命中 / 未命中 DEBUG 日志
    private <T> void logLookupResult(Optional<GenerationStageResult> existing, String keyName, Long keyValue,
                                     String stageName, String inputHash, Class<T> type) {
        if (existing.isPresent()) {
            log.debug("Stage cache hit {}={} stageName={} inputHash={} resultId={} targetType={}",
                    keyName, keyValue, stageName, inputHash, existing.get().getId(), type.getSimpleName());
        } else {
            log.debug("Stage cache miss {}={} stageName={} inputHash={} targetType={}",
                    keyName, keyValue, stageName, inputHash, type.getSimpleName());
        }
    }
    // 强校验，阶段名、哈希不能为空白
    private static String requireStageName(String stageName) {
        if (stageName == null || stageName.isBlank()) {
            throw new IllegalArgumentException("stageName must not be blank");
        }
        return stageName.trim();
    }

    private static String requireHash(String inputHash) {
        if (inputHash == null || inputHash.isBlank()) {
            throw new IllegalArgumentException("inputHash must not be blank");
        }
        return inputHash.trim();
    }

    private static String normalizeHash(@Nullable String inputHash) {
        if (inputHash == null) {
            return null;
        }
        String trimmed = inputHash.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Unknown generation stage failure";
        }
        return errorMessage.trim();
    }
}
