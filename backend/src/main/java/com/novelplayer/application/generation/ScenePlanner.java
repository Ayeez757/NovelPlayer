package com.novelplayer.application.generation;

import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.ScenePlan;
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

/**
 * 场景规划阶段生成器。
 *
 * <p>该阶段只负责把章节摘要和故事圣经规划为剧本场景结构，不生成动作、对白等正文内容。
 * 它会校验场景对章节、人物和地点的引用，避免后续分场草稿阶段拿到不可落地的结构蓝图。</p>
 */
@Service
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class ScenePlanner {

    private static final Logger log = LoggerFactory.getLogger(ScenePlanner.class);

    private final StagedScriptAiClient aiClient;
    private final GenerationStageStore stageStore;

    /**
     * 创建场景规划阶段生成器。
     *
     * @param aiClient 阶段化 AI 客户端。
     * @param stageStore 生成阶段结果存取层。
     */
    public ScenePlanner(StagedScriptAiClient aiClient, GenerationStageStore stageStore) {
        this.aiClient = aiClient;
        this.stageStore = stageStore;
    }

    /**
     * 生成或复用场景规划。
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要。
     * @param storyBible 全局故事圣经。
     * @param options 生成参数。
     * @return 场景规划结果，只包含结构大纲，不包含正文。
     */
    public ScenePlan plan(GenerationJob job, NovelProject project, List<ChapterDigest> chapterDigests,
                          StoryBible storyBible, GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(storyBible, "storyBible must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<ChapterDigest> digests = requireChapterDigests(chapterDigests);

        String stageName = GenerationStageNames.SCENE_PLAN;
        String inputHash = stageStore.sha256OfJson(ScenePlanInput.from(project, digests, storyBible, options));
        log.info("开始生成场景规划 jobId={} projectId={} digestCount={} characterCount={} locationCount={} stageName={} inputHash={}",
                job.getId(), project.getId(), digests.size(), storyBible.characters().size(),
                storyBible.locations().size(), stageName, inputHash);

        Optional<ScenePlan> cached = stageStore.findSucceeded(job, stageName, inputHash, ScenePlan.class);
        if (cached.isPresent()) {
            ScenePlan scenePlan = cached.orElseThrow();
            validate(scenePlan, digests, storyBible);
            log.info("复用已存在的场景规划 jobId={} projectId={} sceneCount={}",
                    job.getId(), project.getId(), scenePlan.scenes().size());
            return scenePlan;
        }

        try {
            ScenePlan scenePlan = aiClient.generateScenePlan(project, digests, storyBible, options);
            validate(scenePlan, digests, storyBible);
            stageStore.saveSucceeded(job, stageName, inputHash, scenePlan);
            log.info("场景规划生成并保存成功 jobId={} projectId={} sceneCount={} coveredChapterCount={}",
                    job.getId(), project.getId(), scenePlan.scenes().size(), collectChapterIndexes(digests).size());
            return scenePlan;
        } catch (RuntimeException exception) {
            stageStore.saveFailed(job, stageName, inputHash, exception.getMessage());
            log.warn("场景规划生成失败 jobId={} projectId={} stageName={} error={}",
                    job.getId(), project.getId(), stageName, exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 校验场景规划和当前章节摘要、故事圣经之间的一致性。
     *
     * @param scenePlan 待校验的场景规划。
     * @param chapterDigests 当前输入章节摘要。
     * @param storyBible 当前故事圣经。
     */
    private static void validate(ScenePlan scenePlan, List<ChapterDigest> chapterDigests, StoryBible storyBible) {
        Objects.requireNonNull(scenePlan, "scenePlan must not be null");
        Set<Integer> availableChapterIndexes = collectChapterIndexes(chapterDigests);
        Set<String> availableCharacterIds = collectCharacterIds(storyBible);
        Set<String> availableLocationIds = collectLocationIds(storyBible);
        Set<String> sceneIds = new HashSet<>();

        for (PlannedScene scene : scenePlan.scenes()) {
            validateSceneId(scene, sceneIds);
            validateSourceChapters(scene, availableChapterIndexes);
            validateLocation(scene, availableLocationIds);
            validateCharacters(scene, availableCharacterIds);
        }
    }

    /**
     * 校验场景编号不重复。
     *
     * @param scene 待校验场景。
     * @param sceneIds 已出现的场景编号集合。
     */
    private static void validateSceneId(PlannedScene scene, Set<String> sceneIds) {
        if (!sceneIds.add(scene.id())) {
            throw new IllegalArgumentException("scene id must be unique: " + scene.id());
        }
    }

    /**
     * 校验场景引用的原文章节都存在于当前章节摘要集合中。
     *
     * @param scene 待校验场景。
     * @param availableChapterIndexes 可引用的章节编号集合。
     */
    private static void validateSourceChapters(PlannedScene scene, Set<Integer> availableChapterIndexes) {
        for (Integer chapterIndex : scene.sourceChapters()) {
            if (!availableChapterIndexes.contains(chapterIndex)) {
                throw new IllegalArgumentException("scene source chapter must exist in chapter digests: "
                        + scene.id() + " -> " + chapterIndex);
            }
        }
    }

    /**
     * 校验场景引用的地点存在于故事圣经地点表。
     *
     * @param scene 待校验场景。
     * @param availableLocationIds 可引用的地点编号集合。
     */
    private static void validateLocation(PlannedScene scene, Set<String> availableLocationIds) {
        if (!availableLocationIds.contains(scene.locationId())) {
            throw new IllegalArgumentException("scene location must exist in story bible: "
                    + scene.id() + " -> " + scene.locationId());
        }
    }

    /**
     * 校验场景出场人物都存在于故事圣经人物表。
     *
     * @param scene 待校验场景。
     * @param availableCharacterIds 可引用的人物编号集合。
     */
    private static void validateCharacters(PlannedScene scene, Set<String> availableCharacterIds) {
        for (String characterId : scene.characters()) {
            if (!availableCharacterIds.contains(characterId)) {
                throw new IllegalArgumentException("scene character must exist in story bible: "
                        + scene.id() + " -> " + characterId);
            }
        }
    }

    /**
     * 收集当前章节摘要中真实可引用的章节编号。
     *
     * @param chapterDigests 章节摘要列表。
     * @return 章节编号集合。
     */
    private static Set<Integer> collectChapterIndexes(List<ChapterDigest> chapterDigests) {
        Set<Integer> chapterIndexes = new HashSet<>();
        for (ChapterDigest digest : chapterDigests) {
            if (!chapterIndexes.add(digest.chapterIndex())) {
                throw new IllegalArgumentException("chapter digest index must be unique: " + digest.chapterIndex());
            }
        }
        return chapterIndexes;
    }

    /**
     * 收集故事圣经中可引用的人物编号。
     *
     * @param storyBible 故事圣经。
     * @return 人物编号集合。
     */
    private static Set<String> collectCharacterIds(StoryBible storyBible) {
        Set<String> characterIds = new HashSet<>();
        for (BibleCharacter character : storyBible.characters()) {
            if (!characterIds.add(character.id())) {
                throw new IllegalArgumentException("story bible character id must be unique: " + character.id());
            }
        }
        return characterIds;
    }

    /**
     * 收集故事圣经中可引用的地点编号。
     *
     * @param storyBible 故事圣经。
     * @return 地点编号集合。
     */
    private static Set<String> collectLocationIds(StoryBible storyBible) {
        Set<String> locationIds = new HashSet<>();
        for (BibleLocation location : storyBible.locations()) {
            if (!locationIds.add(location.id())) {
                throw new IllegalArgumentException("story bible location id must be unique: " + location.id());
            }
        }
        return locationIds;
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
     * 场景规划阶段的输入快照。
     *
     * @param projectId 项目主键。
     * @param projectTitle 项目标题。
     * @param chapterDigests 章节摘要列表。
     * @param storyBible 故事圣经。
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param dialogueDensity 对白密度。
     * @param narrationRetention 旁白保留度。
     * @param hasAdditionalInstructions 是否存在用户补充要求。
     * @param additionalInstructions 用户补充要求。
     */
    private record ScenePlanInput(
            Long projectId,
            String projectTitle,
            List<ChapterDigest> chapterDigests,
            StoryBible storyBible,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
        /**
         * 从当前场景规划上下文构造输入快照。
         *
         * @param project 小说改编项目。
         * @param chapterDigests 章节摘要列表。
         * @param storyBible 故事圣经。
         * @param options 生成参数。
         * @return 用于哈希计算的输入快照。
         */
        private static ScenePlanInput from(NovelProject project, List<ChapterDigest> chapterDigests,
                                           StoryBible storyBible, GenerationOptions options) {
            return new ScenePlanInput(
                    project.getId(),
                    project.getTitle(),
                    chapterDigests,
                    storyBible,
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
