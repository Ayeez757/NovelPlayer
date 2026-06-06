package com.novelplayer.application.generation;

import com.novelplayer.ai.StagedScriptAiClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.SceneDraftContext;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 分场草稿阶段生成器。
 *
 * <p>该阶段按 {@link ScenePlan#scenes()} 顺序逐场生成正文草稿，每一场只携带当前场景需要的
 * 最小上下文，并以 {@code scene_draft:<sceneId>} 独立落库，方便后续支持单场重生成。</p>
 */
@Service
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class SceneDraftGenerator {

    private static final Logger log = LoggerFactory.getLogger(SceneDraftGenerator.class);

    private final StagedScriptAiClient aiClient;
    private final GenerationStageStore stageStore;

    /**
     * 创建分场草稿阶段生成器。
     *
     * @param aiClient 阶段化 AI 客户端。
     * @param stageStore 生成阶段结果存取层。
     */
    public SceneDraftGenerator(StagedScriptAiClient aiClient, GenerationStageStore stageStore) {
        this.aiClient = aiClient;
        this.stageStore = stageStore;
    }

    /**
     * 按场景规划顺序生成或复用所有分场草稿。
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapters 按章节顺序排列的小说原文章节。
     * @param scenePlan 场景规划。
     * @param storyBible 全局故事圣经。
     * @param options 生成参数。
     * @return 与场景规划顺序一致的分场草稿列表。
     */
    public List<SceneDraft> generate(GenerationJob job, NovelProject project, List<NovelChapter> chapters,
                                     ScenePlan scenePlan, StoryBible storyBible, GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(scenePlan, "scenePlan must not be null");
        Objects.requireNonNull(storyBible, "storyBible must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Map<Integer, NovelChapter> chaptersByIndex = indexChapters(chapters);
        Map<String, BibleCharacter> charactersById = indexCharacters(storyBible.characters());
        Map<String, BibleLocation> locationsById = indexLocations(storyBible.locations());
        validateSceneIds(scenePlan);

        log.info("开始生成分场草稿 jobId={} projectId={} sceneCount={} chapterCount={} characterCount={} locationCount={}",
                job.getId(), project.getId(), scenePlan.scenes().size(), chaptersByIndex.size(),
                charactersById.size(), locationsById.size());

        List<SceneDraft> drafts = new ArrayList<>(scenePlan.scenes().size());
        String previousSceneSummary = null;
        for (PlannedScene scene : scenePlan.scenes()) {
            SceneDraft draft = generateOne(job, project, scene, chaptersByIndex, charactersById,
                    locationsById, storyBible.continuityRules(), previousSceneSummary, options);
            drafts.add(draft);
            previousSceneSummary = draft.summary();
        }

        log.info("分场草稿阶段完成 jobId={} projectId={} sceneCount={} draftCount={}",
                job.getId(), project.getId(), scenePlan.scenes().size(), drafts.size());
        return List.copyOf(drafts);
    }

    /**
     * 生成或复用单个分场草稿。
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param scene 当前场景规划。
     * @param chaptersByIndex 章节编号到原文章节的索引。
     * @param charactersById 人物编号到人物资料的索引。
     * @param locationsById 地点编号到地点资料的索引。
     * @param continuityRules 全局连续性规则。
     * @param previousSceneSummary 前一个场景摘要，可为空。
     * @param options 生成参数。
     * @return 单场分场草稿。
     */
    private SceneDraft generateOne(GenerationJob job, NovelProject project, PlannedScene scene,
                                   Map<Integer, NovelChapter> chaptersByIndex,
                                   Map<String, BibleCharacter> charactersById,
                                   Map<String, BibleLocation> locationsById,
                                   List<String> continuityRules,
                                   @Nullable String previousSceneSummary,
                                   GenerationOptions options) {
        String stageName = GenerationStageNames.sceneDraft(scene.id());
        String inputHash = null;
        try {
            SceneDraftContext context = buildContext(scene, chaptersByIndex, charactersById,
                    locationsById, continuityRules, previousSceneSummary);
            inputHash = stageStore.sha256OfJson(SceneDraftInput.from(project, context, options));
            log.info("开始生成单场草稿 jobId={} projectId={} sceneId={} stageName={} inputHash={} sourceChapterCount={} characterCount={}",
                    job.getId(), project.getId(), scene.id(), stageName, inputHash,
                    context.sourceChapters().size(), context.characters().size());

            Optional<SceneDraft> cached = stageStore.findSucceeded(job, stageName, inputHash, SceneDraft.class);
            if (cached.isPresent()) {
                SceneDraft draft = cached.orElseThrow();
                validateDraft(draft, context);
                log.info("复用已存在的分场草稿 jobId={} projectId={} sceneId={} blockCount={}",
                        job.getId(), project.getId(), scene.id(), draft.blocks().size());
                return draft;
            }

            SceneDraft draft = aiClient.generateSceneDraft(project, context, options);
            validateDraft(draft, context);
            stageStore.saveSucceeded(job, stageName, inputHash, draft);
            log.info("分场草稿生成并保存成功 jobId={} projectId={} sceneId={} blockCount={} summaryLength={}",
                    job.getId(), project.getId(), scene.id(), draft.blocks().size(), draft.summary().length());
            return draft;
        } catch (RuntimeException exception) {
            stageStore.saveFailed(job, stageName, inputHash, exception.getMessage());
            log.warn("分场草稿生成失败 jobId={} projectId={} sceneId={} stageName={} error={}",
                    job.getId(), project.getId(), scene.id(), stageName, exception.getMessage(), exception);
            throw exception;
        }
    }

    /**
     * 构造单场写作的最小上下文。
     *
     * @param scene 当前场景规划。
     * @param chaptersByIndex 章节编号到原文章节的索引。
     * @param charactersById 人物编号到人物资料的索引。
     * @param locationsById 地点编号到地点资料的索引。
     * @param continuityRules 全局连续性规则。
     * @param previousSceneSummary 前一个场景摘要，可为空。
     * @return 分场写作上下文。
     */
    private static SceneDraftContext buildContext(PlannedScene scene,
                                                  Map<Integer, NovelChapter> chaptersByIndex,
                                                  Map<String, BibleCharacter> charactersById,
                                                  Map<String, BibleLocation> locationsById,
                                                  List<String> continuityRules,
                                                  @Nullable String previousSceneSummary) {
        List<NovelChapter> sourceChapters = scene.sourceChapters().stream()
                .map(chapterIndex -> requireChapter(chaptersByIndex, scene.id(), chapterIndex))
                .toList();
        List<BibleCharacter> characters = scene.characters().stream()
                .map(characterId -> requireCharacter(charactersById, scene.id(), characterId))
                .toList();
        BibleLocation location = requireLocation(locationsById, scene.id(), scene.locationId());
        return new SceneDraftContext(scene, sourceChapters, characters, location, continuityRules, previousSceneSummary);
    }

    /**
     * 校验 AI 返回的草稿仍然匹配当前场景规划和最小上下文。
     *
     * @param draft 待校验分场草稿。
     * @param context 当前分场写作上下文。
     */
    private static void validateDraft(SceneDraft draft, SceneDraftContext context) {
        Objects.requireNonNull(draft, "sceneDraft must not be null");
        PlannedScene scene = context.plannedScene();
        if (!draft.id().equals(scene.id())) {
            throw new IllegalArgumentException("scene draft id must match planned scene: " + draft.id());
        }
        if (!draft.sourceChapters().equals(scene.sourceChapters())) {
            throw new IllegalArgumentException("scene draft sourceChapters must match planned scene: " + scene.id());
        }
        if (!draft.locationId().equals(scene.locationId())) {
            throw new IllegalArgumentException("scene draft locationId must match planned scene: " + scene.id());
        }
        if (!draft.characters().equals(scene.characters())) {
            throw new IllegalArgumentException("scene draft characters must match planned scene: " + scene.id());
        }
        validateBlockSpeakers(draft, context);
    }

    /**
     * 校验分场正文块中的说话人引用只来自当前场景人物。
     *
     * @param draft 待校验分场草稿。
     * @param context 当前分场写作上下文。
     */
    private static void validateBlockSpeakers(SceneDraft draft, SceneDraftContext context) {
        Set<String> availableCharacterIds = new HashSet<>();
        context.characters().forEach(character -> availableCharacterIds.add(character.id()));
        for (DraftSceneBlock block : draft.blocks()) {
            if (block.speakerId() != null && !availableCharacterIds.contains(block.speakerId())) {
                throw new IllegalArgumentException("scene draft block speaker must exist in context: "
                        + draft.id() + " -> " + block.speakerId());
            }
        }
    }

    /**
     * 建立章节编号索引，并校验章节列表可用于分场生成。
     *
     * @param chapters 原文章节列表。
     * @return 章节编号到章节实体的索引。
     */
    private static Map<Integer, NovelChapter> indexChapters(List<NovelChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("chapters must not be empty");
        }
        Map<Integer, NovelChapter> chaptersByIndex = new HashMap<>();
        for (NovelChapter chapter : chapters) {
            Objects.requireNonNull(chapter, "chapters must not contain null");
            NovelChapter previous = chaptersByIndex.putIfAbsent(chapter.getChapterIndex(), chapter);
            if (previous != null) {
                throw new IllegalArgumentException("chapter index must be unique: " + chapter.getChapterIndex());
            }
        }
        return Map.copyOf(chaptersByIndex);
    }

    /**
     * 建立人物编号索引，并校验人物编号不重复。
     *
     * @param characters 故事圣经人物列表。
     * @return 人物编号到人物资料的索引。
     */
    private static Map<String, BibleCharacter> indexCharacters(List<BibleCharacter> characters) {
        Map<String, BibleCharacter> charactersById = new HashMap<>();
        for (BibleCharacter character : characters) {
            BibleCharacter previous = charactersById.putIfAbsent(character.id(), character);
            if (previous != null) {
                throw new IllegalArgumentException("story bible character id must be unique: " + character.id());
            }
        }
        return Map.copyOf(charactersById);
    }

    /**
     * 建立地点编号索引，并校验地点编号不重复。
     *
     * @param locations 故事圣经地点列表。
     * @return 地点编号到地点资料的索引。
     */
    private static Map<String, BibleLocation> indexLocations(List<BibleLocation> locations) {
        Map<String, BibleLocation> locationsById = new HashMap<>();
        for (BibleLocation location : locations) {
            BibleLocation previous = locationsById.putIfAbsent(location.id(), location);
            if (previous != null) {
                throw new IllegalArgumentException("story bible location id must be unique: " + location.id());
            }
        }
        return Map.copyOf(locationsById);
    }

    /**
     * 校验场景规划中场景编号不重复。
     *
     * @param scenePlan 场景规划。
     */
    private static void validateSceneIds(ScenePlan scenePlan) {
        Set<String> sceneIds = new HashSet<>();
        for (PlannedScene scene : scenePlan.scenes()) {
            if (!sceneIds.add(scene.id())) {
                throw new IllegalArgumentException("scene id must be unique: " + scene.id());
            }
        }
    }

    /**
     * 从章节索引中读取当前场景引用的原文章节。
     *
     * @param chaptersByIndex 章节编号索引。
     * @param sceneId 场景编号。
     * @param chapterIndex 章节编号。
     * @return 原文章节。
     */
    private static NovelChapter requireChapter(Map<Integer, NovelChapter> chaptersByIndex, String sceneId,
                                               Integer chapterIndex) {
        NovelChapter chapter = chaptersByIndex.get(chapterIndex);
        if (chapter == null) {
            throw new IllegalArgumentException("scene source chapter must exist in chapters: "
                    + sceneId + " -> " + chapterIndex);
        }
        return chapter;
    }

    /**
     * 从人物索引中读取当前场景引用的人物资料。
     *
     * @param charactersById 人物编号索引。
     * @param sceneId 场景编号。
     * @param characterId 人物编号。
     * @return 人物资料。
     */
    private static BibleCharacter requireCharacter(Map<String, BibleCharacter> charactersById, String sceneId,
                                                   String characterId) {
        BibleCharacter character = charactersById.get(characterId);
        if (character == null) {
            throw new IllegalArgumentException("scene character must exist in story bible: "
                    + sceneId + " -> " + characterId);
        }
        return character;
    }

    /**
     * 从地点索引中读取当前场景引用的地点资料。
     *
     * @param locationsById 地点编号索引。
     * @param sceneId 场景编号。
     * @param locationId 地点编号。
     * @return 地点资料。
     */
    private static BibleLocation requireLocation(Map<String, BibleLocation> locationsById, String sceneId,
                                                 String locationId) {
        BibleLocation location = locationsById.get(locationId);
        if (location == null) {
            throw new IllegalArgumentException("scene location must exist in story bible: "
                    + sceneId + " -> " + locationId);
        }
        return location;
    }

    /**
     * 分场草稿阶段的输入快照。
     *
     * @param projectId 项目主键。
     * @param projectTitle 项目标题。
     * @param plannedScene 当前场景规划。
     * @param sourceChapters 当前场景引用的原文章节快照。
     * @param characters 当前场景涉及的人物资料。
     * @param location 当前场景涉及的地点资料。
     * @param continuityRules 全局连续性规则。
     * @param previousSceneSummary 前一个场景摘要。
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param dialogueDensity 对白密度。
     * @param narrationRetention 旁白保留度。
     * @param hasAdditionalInstructions 是否存在用户补充要求。
     * @param additionalInstructions 用户补充要求。
     */
    private record SceneDraftInput(
            Long projectId,
            String projectTitle,
            PlannedScene plannedScene,
            List<SourceChapterInput> sourceChapters,
            List<BibleCharacter> characters,
            BibleLocation location,
            List<String> continuityRules,
            String previousSceneSummary,
            String format,
            String tone,
            int dialogueDensity,
            int narrationRetention,
            boolean hasAdditionalInstructions,
            String additionalInstructions
    ) {
        /**
         * 从当前分场写作上下文构造输入快照。
         *
         * @param project 小说改编项目。
         * @param context 分场写作上下文。
         * @param options 生成参数。
         * @return 用于哈希计算的输入快照。
         */
        private static SceneDraftInput from(NovelProject project, SceneDraftContext context, GenerationOptions options) {
            return new SceneDraftInput(
                    project.getId(),
                    project.getTitle(),
                    context.plannedScene(),
                    context.sourceChapters().stream()
                            .map(SourceChapterInput::from)
                            .toList(),
                    context.characters(),
                    context.location(),
                    context.continuityRules(),
                    context.previousSceneSummary(),
                    options.format(),
                    options.tone(),
                    options.dialogueDensity(),
                    options.narrationRetention(),
                    options.hasAdditionalInstructions(),
                    options.additionalInstructions()
            );
        }
    }

    /**
     * 分场草稿阶段引用原文章节的输入快照。
     *
     * @param chapterId 章节主键。
     * @param chapterIndex 章节序号。
     * @param title 章节标题。
     * @param content 章节正文。
     */
    private record SourceChapterInput(
            Long chapterId,
            int chapterIndex,
            String title,
            String content
    ) {
        /**
         * 从小说章节实体构造稳定输入快照，避免直接序列化 JPA 实体。
         *
         * @param chapter 小说章节实体。
         * @return 原文章节输入快照。
         */
        private static SourceChapterInput from(NovelChapter chapter) {
            return new SourceChapterInput(
                    chapter.getId(),
                    chapter.getChapterIndex(),
                    chapter.getTitle(),
                    chapter.getContent()
            );
        }
    }
}
