package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.generation.GenerationStageResult;
import com.novelplayer.domain.generation.GenerationStatus;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.infra.repository.GenerationStageResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 记录一次生成的输入参数快照，便于后续排查同一项目的不同生成结果。
 */
@Component
public class GenerationInputSnapshotRecorder {

    private static final Logger log = LoggerFactory.getLogger(GenerationInputSnapshotRecorder.class);

    private static final String STAGE_NAME = "generation_input";

    private final GenerationStageResultRepository stageResultRepository;
    private final ObjectMapper objectMapper;

    /**
     * 注入阶段结果仓储和 JSON 序列化器。
     *
     * @param stageResultRepository 阶段结果仓储。
     * @param objectMapper JSON 序列化器。
     */
    public GenerationInputSnapshotRecorder(GenerationStageResultRepository stageResultRepository,
                                           ObjectMapper objectMapper) {
        this.stageResultRepository = stageResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存生成输入快照。快照只保存参数和章节数量，不重复保存完整原文或内部提示词。
     *
     * @param job 生成任务。
     * @param project 小说改编项目。
     * @param chapters 本次参与生成的章节。
     * @param options 生成选项。
     */
    public void record(GenerationJob job, NovelProject project, List<NovelChapter> chapters, GenerationOptions options) {
        // 只记录可审计的生成参数，不持久化完整 prompt，避免扩大原文和内部规则的暴露面。
        GenerationInputSnapshot snapshot = GenerationInputSnapshot.from(project, chapters, options);
        String snapshotJson = toJson(snapshot);
        String inputHash = sha256(snapshotJson);
        stageResultRepository.save(new GenerationStageResult(job, STAGE_NAME, GenerationStatus.SUCCEEDED,
                inputHash, snapshotJson, null));
        log.debug("Generation input snapshot recorded jobId={} projectId={} inputHash={} snapshotLength={}",
                job.getId(), project.getId(), inputHash, snapshotJson.length());
    }

    private String toJson(GenerationInputSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize generation input snapshot", exception);
        }
    }

    private static String sha256(String value) {
        try {
            // 输入摘要用于后续判断生成条件是否一致，不作为安全签名或鉴权凭据使用。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private record GenerationInputSnapshot(
            Long projectId,
            String title,
            int chapterCount,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
        private static GenerationInputSnapshot from(NovelProject project, List<NovelChapter> chapters,
                                                    GenerationOptions options) {
            return new GenerationInputSnapshot(
                    project.getId(),
                    project.getTitle(),
                    chapters.size(),
                    options.format(),
                    options.tone(),
                    options.dialogueDensity(),
                    options.narrationRetention(),
                    options.hasAdditionalInstructions(),
                    options.additionalInstructions()
            );
        }
    }
}
