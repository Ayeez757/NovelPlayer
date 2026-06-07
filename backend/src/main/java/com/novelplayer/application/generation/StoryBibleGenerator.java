package com.novelplayer.application.generation;

//增加import
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 故事圣经阶段生成器。
 *
 * <p>该阶段基于全部章节摘要生成全局人物、地点、主线和连续性规则，并在后端兜底校验
 * 人物/地点稳定 ID，避免后续场景规划和分场写作引用不可靠的编号。</p>
 */
@Service
//增加注解
@ConditionalOnBean(StagedScriptAiClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class StoryBibleGenerator {

    private static final Logger log = LoggerFactory.getLogger(StoryBibleGenerator.class);

    private static final Pattern CHARACTER_ID_PATTERN = Pattern.compile("char_\\d{3}");
    private static final Pattern LOCATION_ID_PATTERN = Pattern.compile("loc_\\d{3}");

    private final StagedScriptAiClient aiClient;
    private final GenerationStageStore stageStore;

    /**
     * 创建故事圣经阶段生成器。
     *
     * @param aiClient 阶段化 AI 客户端。
     * @param stageStore 生成阶段结果存取层。
     */
    public StoryBibleGenerator(StagedScriptAiClient aiClient, GenerationStageStore stageStore) {
        this.aiClient = aiClient;
        this.stageStore = stageStore;
    }

    /**
     * 生成或复用故事圣经。
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要。
     * @param options 生成参数。
     * @return 全局故事圣经。
     */
    public StoryBible generate(GenerationJob job, NovelProject project, List<ChapterDigest> chapterDigests,
                               GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<ChapterDigest> digests = requireChapterDigests(chapterDigests);

        String stageName = GenerationStageNames.STORY_BIBLE;
        String inputHash = stageStore.sha256OfJson(StoryBibleInput.from(project, digests, options));
        log.info("开始生成故事圣经 jobId={} projectId={} digestCount={} stageName={} inputHash={}",
                job.getId(), project.getId(), digests.size(), stageName, inputHash);

//        Optional<StoryBible> cached = stageStore.findSucceeded(job, stageName, inputHash, StoryBible.class);
        // 用 projectId 查缓存，才能跨 job 复用
        /*
         * 之前的改动把这里切成了 projectId 直调版本：
         * Optional<StoryBible> cached = stageStore.findSucceeded(
         *         project.getId(), stageName, inputHash, StoryBible.class);
         */
        Optional<StoryBible> cached = stageStore.findSucceeded(job, stageName, inputHash, StoryBible.class);

        if (cached.isPresent()) {
            StoryBible bible = cached.orElseThrow();
            validate(bible);
            log.info("复用已存在的故事圣经 jobId={} projectId={} characterCount={} locationCount={}",
                    job.getId(), project.getId(), bible.characters().size(), bible.locations().size());
            return bible;
        }

        try {
            StoryBible bible = aiClient.generateStoryBible(project, digests, options);
            validate(bible);
            stageStore.saveSucceeded(job, stageName, inputHash, bible);
            log.info("故事圣经生成并保存成功 jobId={} projectId={} characterCount={} locationCount={} ruleCount={}",
                    job.getId(), project.getId(), bible.characters().size(), bible.locations().size(),
                    bible.continuityRules().size());
            return bible;
        } catch (RuntimeException exception) {
            stageStore.saveFailed(job, stageName, inputHash, exception.getMessage());
            log.warn("故事圣经生成失败 jobId={} projectId={} stageName={} error={}",
                    job.getId(), project.getId(), stageName, exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 校验故事圣经中的人物和地点编号稳定性。
     *
     * @param bible 待校验故事圣经。
     */
    private static void validate(StoryBible bible) {
        Objects.requireNonNull(bible, "storyBible must not be null");
        validateCharacterIds(bible.characters());
        validateLocationIds(bible.locations());
    }

    /**
     * 校验人物 ID 格式和唯一性。
     *
     * @param characters 人物档案列表。
     */
    private static void validateCharacterIds(List<BibleCharacter> characters) {
        Set<String> ids = new HashSet<>();
        for (BibleCharacter character : characters) {
            if (!CHARACTER_ID_PATTERN.matcher(character.id()).matches()) {
                throw new IllegalArgumentException("character id must match char_001 format: " + character.id());
            }
            if (!ids.add(character.id())) {
                throw new IllegalArgumentException("character id must be unique: " + character.id());
            }
            if (character.name().isBlank()) {
                throw new IllegalArgumentException("character name must not be blank: " + character.id());
            }
        }
    }

    /**
     * 校验地点 ID 格式和唯一性。
     *
     * @param locations 地点档案列表。
     */
    private static void validateLocationIds(List<BibleLocation> locations) {
        Set<String> ids = new HashSet<>();
        for (BibleLocation location : locations) {
            if (!LOCATION_ID_PATTERN.matcher(location.id()).matches()) {
                throw new IllegalArgumentException("location id must match loc_001 format: " + location.id());
            }
            if (!ids.add(location.id())) {
                throw new IllegalArgumentException("location id must be unique: " + location.id());
            }
            if (location.name().isBlank()) {
                throw new IllegalArgumentException("location name must not be blank: " + location.id());
            }
        }
    }

    /**
     * 校验章节摘要列表，并复制为不可变列表。
     *
     * @param chapterDigests 原始章节摘要列表。
     * @return 不可变章节摘要列表。
     */
    private static List<ChapterDigest> requireChapterDigests(List<ChapterDigest> chapterDigests) {
        if (chapterDigests == null || chapterDigests.isEmpty()) {
            throw new IllegalArgumentException("chapterDigests must not be empty");
        }
        return List.copyOf(chapterDigests.stream()
                .map(digest -> Objects.requireNonNull(digest, "chapterDigests must not contain null"))
                .toList());
    }

    /**
     * 故事圣经阶段的输入快照。
     *
     * @param projectId 项目主键。
     * @param projectTitle 项目标题。
     * @param chapterDigests 章节摘要列表。
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param dialogueDensity 对白密度。
     * @param narrationRetention 旁白保留度。
     * @param hasAdditionalInstructions 是否存在用户补充要求。
     * @param additionalInstructions 用户补充要求。
     */
    private record StoryBibleInput(
            Long projectId,
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
        /**
         * 从当前故事圣经上下文构造输入快照。
         *
         * @param project 小说改编项目。
         * @param chapterDigests 章节摘要列表。
         * @param options 生成参数。
         * @return 用于哈希计算的输入快照。
         */
        private static StoryBibleInput from(NovelProject project, List<ChapterDigest> chapterDigests,
                                            GenerationOptions options) {
            return new StoryBibleInput(
                    project.getId(),
                    project.getTitle(),
                    chapterDigests,
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
