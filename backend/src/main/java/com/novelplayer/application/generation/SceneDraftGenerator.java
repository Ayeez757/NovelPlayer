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
import com.novelplayer.config.NovelPlayerProperties;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * <p>该阶段按 {@link ScenePlan#scenes()} 生成正文草稿，每一场只携带当前场景需要的最小上下文，
 * 并以 {@code scene_draft:<sceneId>} 独立落库，方便后续复用或单场重生成。</p>
 */
@Service
@ConditionalOnBean(StagedScriptAiClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class SceneDraftGenerator {

    private static final Logger log = LoggerFactory.getLogger(SceneDraftGenerator.class);

    private final StagedScriptAiClient aiClient;
    private final GenerationStageStore stageStore;
    private final ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider;
    private final NovelPlayerProperties properties;
    private final GenerationStageParallelExecutor parallelExecutor;

    /**
     * 创建分场草稿阶段生成器。
     *
     * @param aiClient 阶段化 AI 客户端。
     * @param stageStore 生成阶段结果存取层。
     * @param lifecycleServiceProvider 任务生命周期服务，测试场景下可为空。
     * @param properties 应用配置。
     * @param parallelExecutor 阶段内有界并行执行器。
     */
    public SceneDraftGenerator(StagedScriptAiClient aiClient,
                               GenerationStageStore stageStore,
                               ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider,
                               NovelPlayerProperties properties,
                               GenerationStageParallelExecutor parallelExecutor) {
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient must not be null");
        this.stageStore = Objects.requireNonNull(stageStore, "stageStore must not be null");
        this.lifecycleServiceProvider = Objects.requireNonNull(
                lifecycleServiceProvider, "lifecycleServiceProvider must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.parallelExecutor = Objects.requireNonNull(parallelExecutor, "parallelExecutor must not be null");
    }

    /**
     * 按场景规划顺序生成或复用所有分场草稿。
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapters 按章节顺序排列的小说原文章节。
     * @param scenePlan 场景规划。
     * @param storyBible 全局故事设定集。
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
        int concurrency = Math.min(
                properties.getGeneration().getSceneDraftConcurrency(),
                scenePlan.scenes().size()
        );

        log.info("开始生成分场草稿 jobId={} projectId={} sceneCount={} chapterCount={} characterCount={} "
                        + "locationCount={} concurrency={}",
                job.getId(), project.getId(), scenePlan.scenes().size(), chaptersByIndex.size(),
                charactersById.size(), locationsById.size(), concurrency);

        List<SceneDraft> drafts;
        if (concurrency == 1) {
            drafts = generateSerial(job, project, scenePlan.scenes(), chaptersByIndex, charactersById,
                    locationsById, storyBible.continuityRules(), options);
        } else {
            drafts = generateParallel(job, project, scenePlan.scenes(), chaptersByIndex, charactersById,
                    locationsById, storyBible.continuityRules(), options, concurrency);
        }

        log.info("分场草稿阶段完成 jobId={} projectId={} sceneCount={} draftCount={}",
                job.getId(), project.getId(), scenePlan.scenes().size(), drafts.size());
        return List.copyOf(drafts);
    }

    private List<SceneDraft> generateSerial(GenerationJob job, NovelProject project, List<PlannedScene> scenes,
                                            Map<Integer, NovelChapter> chaptersByIndex,
                                            Map<String, BibleCharacter> charactersById,
                                            Map<String, BibleLocation> locationsById,
                                            List<String> continuityRules,
                                            GenerationOptions options) {
        List<SceneDraft> drafts = new ArrayList<>(scenes.size());
        String previousSceneSummary = null;
        for (PlannedScene scene : scenes) {
            SceneDraft draft = generateOne(job, project, scene, chaptersByIndex, charactersById,
                    locationsById, continuityRules, previousSceneSummary, options, true);
            drafts.add(draft);
            previousSceneSummary = draft.summary();
        }
        return drafts;
    }

    private List<SceneDraft> generateParallel(GenerationJob job, NovelProject project, List<PlannedScene> scenes,
                                              Map<Integer, NovelChapter> chaptersByIndex,
                                              Map<String, BibleCharacter> charactersById,
                                              Map<String, BibleLocation> locationsById,
                                              List<String> continuityRules,
                                              GenerationOptions options,
                                              int concurrency) {
        List<SceneTask> tasks = new ArrayList<>(scenes.size());
        for (int index = 0; index < scenes.size(); index++) {
            /*
             * 并行模式不能等待上一场的实际生成摘要，否则会退化为串行。
             * 因此这里使用上一场规划摘要作为邻近上下文，牺牲少量跨场即时反馈，换取稳定的并发加速。
             */
            String previousPlannedSummary = index == 0 ? null : scenes.get(index - 1).summary();
            tasks.add(new SceneTask(scenes.get(index), previousPlannedSummary));
        }

        return parallelExecutor.runOrdered(
                GenerationStageNames.SCENE_DRAFT,
                tasks,
                concurrency,
                task -> generateOne(job, project, task.scene(), chaptersByIndex, charactersById,
                        locationsById, continuityRules, task.previousSceneSummary(), options, false)
        );
    }

    /**
     * 生成或复用单个分场草稿。
     *
     * @param updateCurrentStage 是否把任务阶段推进到当前细粒度阶段；并行模式下应关闭。
     */
    private SceneDraft generateOne(GenerationJob job, NovelProject project, PlannedScene scene,
                                   Map<Integer, NovelChapter> chaptersByIndex,
                                   Map<String, BibleCharacter> charactersById,
                                   Map<String, BibleLocation> locationsById,
                                   List<String> continuityRules,
                                   @Nullable String previousSceneSummary,
                                   GenerationOptions options,
                                   boolean updateCurrentStage) {
        String stageName = GenerationStageNames.sceneDraft(scene.id());
        if (updateCurrentStage) {
            moveJobToStage(job, stageName);
        }
        String inputHash = null;
        try {
            SceneDraftContext context = buildContext(scene, chaptersByIndex, charactersById,
                    locationsById, continuityRules, previousSceneSummary);
            inputHash = stageStore.sha256OfJson(SceneDraftInput.from(project, context, options));
            log.info("开始生成单场草稿 jobId={} projectId={} sceneId={} stageName={} inputHash={} "
                            + "sourceChapterCount={} characterCount={}",
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
     * 尽力把当前细粒度阶段写回任务表，供串行模式下的前端轮询展示使用。
     */
    private void moveJobToStage(GenerationJob job, String stageName) {
        GenerationJobLifecycleService lifecycleService = lifecycleServiceProvider.getIfAvailable();
        if (lifecycleService != null && job.getId() != null) {
            lifecycleService.moveToStage(job.getId(), stageName);
        }
    }

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

    private static void validateSceneIds(ScenePlan scenePlan) {
        Set<String> sceneIds = new HashSet<>();
        for (PlannedScene scene : scenePlan.scenes()) {
            if (!sceneIds.add(scene.id())) {
                throw new IllegalArgumentException("scene id must be unique: " + scene.id());
            }
        }
    }

    private static NovelChapter requireChapter(Map<Integer, NovelChapter> chaptersByIndex, String sceneId,
                                               Integer chapterIndex) {
        NovelChapter chapter = chaptersByIndex.get(chapterIndex);
        if (chapter == null) {
            throw new IllegalArgumentException("scene source chapter must exist in chapters: "
                    + sceneId + " -> " + chapterIndex);
        }
        return chapter;
    }

    private static BibleCharacter requireCharacter(Map<String, BibleCharacter> charactersById, String sceneId,
                                                   String characterId) {
        BibleCharacter character = charactersById.get(characterId);
        if (character == null) {
            throw new IllegalArgumentException("scene character must exist in story bible: "
                    + sceneId + " -> " + characterId);
        }
        return character;
    }

    private static BibleLocation requireLocation(Map<String, BibleLocation> locationsById, String sceneId,
                                                 String locationId) {
        BibleLocation location = locationsById.get(locationId);
        if (location == null) {
            throw new IllegalArgumentException("scene location must exist in story bible: "
                    + sceneId + " -> " + locationId);
        }
        return location;
    }

    private record SceneTask(PlannedScene scene, String previousSceneSummary) {
    }

    /**
     * 分场草稿阶段的输入快照。
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
     */
    private record SourceChapterInput(
            Long chapterId,
            int chapterIndex,
            String title,
            String content
    ) {
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
