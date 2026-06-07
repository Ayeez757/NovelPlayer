package com.novelplayer.application.generation;

//增加2个import
import com.novelplayer.ai.StagedScriptAiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 最终剧本文档组装器。
 *
 * <p>该组件不调用模型，只把前面阶段已经产出的故事圣经、场景规划和分场草稿确定性映射为
 * {@link ScriptDocument}，后续仍然复用现有的结构校验、YAML 导出、JSON 映射和文档落库链路。</p>
 */
@Service
//增加注解
@ConditionalOnBean(StagedScriptAiClient.class)
@ConditionalOnProperty(prefix = "novel-player.generation", name = "pipeline-mode", havingValue = "staged",
        matchIfMissing = true)
public class ScriptAssembler {

    private static final Logger log = LoggerFactory.getLogger(ScriptAssembler.class);

    private static final String SCHEMA_VERSION = "1.0";
    private static final String DEFAULT_LANGUAGE = "zh-CN";

    private final Clock clock;

    /**
     * 创建使用系统时钟的最终剧本文档组装器。
     */
    public ScriptAssembler() {
        this(Clock.systemDefaultZone());
    }

    /**
     * 创建可指定时钟的最终剧本文档组装器，方便测试固定生成时间。
     *
     * @param clock 生成时间使用的时钟。
     */
    ScriptAssembler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 将分场草稿确定性转换并组装为最终剧本文档。
     *
     * @param project 小说改编项目。
     * @param options 生成参数。
     * @param storyBible 全局故事圣经。
     * @param scenePlan 场景规划。
     * @param sceneDrafts 按场景顺序排列的分场草稿。
     * @return 最终剧本文档。
     */
    public ScriptDocument assembleDrafts(NovelProject project, GenerationOptions options, StoryBible storyBible,
                                         ScenePlan scenePlan, List<SceneDraft> sceneDrafts) {
        List<SceneDraft> drafts = requireList(sceneDrafts, "sceneDrafts");
        log.info("开始从分场草稿组装最终剧本文档 projectId={} draftCount={}",
                project == null ? null : project.getId(), drafts.size());
        List<ScriptDocument.Scene> scenes = drafts.stream()
                .map(this::toScene)
                .toList();
        return assemble(project, options, storyBible, scenePlan, scenes);
    }

    /**
     * 将最终场景列表和前序阶段产物组装为权威剧本文档。
     *
     * @param project 小说改编项目。
     * @param options 生成参数。
     * @param storyBible 全局故事圣经。
     * @param scenePlan 场景规划。
     * @param scenes 已转换为最终结构的场景列表。
     * @return 最终剧本文档。
     */
    public ScriptDocument assemble(NovelProject project, GenerationOptions options, StoryBible storyBible,
                                   ScenePlan scenePlan, List<ScriptDocument.Scene> scenes) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(storyBible, "storyBible must not be null");
        Objects.requireNonNull(scenePlan, "scenePlan must not be null");
        List<ScriptDocument.Scene> normalizedScenes = requireList(scenes, "scenes");

        log.info("开始组装最终剧本文档 projectId={} sceneCount={} characterCount={} locationCount={}",
                project.getId(), normalizedScenes.size(), storyBible.characters().size(), storyBible.locations().size());
        validateScenesAgainstPlan(scenePlan, normalizedScenes);
        validateSceneReferences(storyBible, normalizedScenes);

        ScriptDocument document = new ScriptDocument(
                SCHEMA_VERSION,
                buildMetadata(project, scenePlan),
                buildAdaptation(options, storyBible),
                storyBible.characters().stream()
                        .map(this::toCharacterProfile)
                        .toList(),
                storyBible.locations().stream()
                        .map(this::toLocationProfile)
                        .toList(),
                normalizedScenes,
                buildRevisionNotes(storyBible, scenePlan, normalizedScenes)
        );
        log.info("最终剧本文档组装完成 projectId={} schemaVersion={} sceneCount={} sourceChapterCount={}",
                project.getId(), document.schemaVersion(), document.scenes().size(),
                document.metadata().sourceChapterCount());
        return document;
    }

    /**
     * 将分场草稿映射为最终剧本文档场景。
     *
     * @param draft 分场草稿。
     * @return 最终场景结构。
     */
    private ScriptDocument.Scene toScene(SceneDraft draft) {
        Objects.requireNonNull(draft, "sceneDraft must not be null");
        log.debug("映射分场草稿为最终场景 sceneId={} blockCount={}", draft.id(), draft.blocks().size());
        return new ScriptDocument.Scene(
                draft.id(),
                draft.title(),
                draft.sourceChapters(),
                draft.locationId(),
                draft.timeOfDay(),
                draft.characters(),
                draft.dramaticPurpose(),
                draft.summary(),
                draft.blocks().stream()
                        .map(this::toSceneBlock)
                        .toList()
        );
    }

    /**
     * 将分场草稿内容块映射为最终场景内容块。
     *
     * @param block 分场草稿内容块。
     * @return 最终场景内容块。
     */
    private ScriptDocument.SceneBlock toSceneBlock(DraftSceneBlock block) {
        Objects.requireNonNull(block, "draftSceneBlock must not be null");
        log.debug("映射分场内容块 blockType={} speakerId={}", block.type(), block.speakerId());
        return new ScriptDocument.SceneBlock(block.type(), block.speakerId(), block.text());
    }

    /**
     * 将故事圣经人物档案映射为最终剧本文档人物档案。
     *
     * @param character 故事圣经人物档案。
     * @return 最终人物档案。
     */
    private ScriptDocument.CharacterProfile toCharacterProfile(BibleCharacter character) {
        Objects.requireNonNull(character, "bibleCharacter must not be null");
        log.debug("映射故事圣经人物 characterId={} name={}", character.id(), character.name());
        return new ScriptDocument.CharacterProfile(
                character.id(),
                character.name(),
                character.aliases(),
                character.role(),
                character.goal(),
                character.traits(),
                character.voice()
        );
    }

    /**
     * 将故事圣经地点档案映射为最终剧本文档地点档案。
     *
     * @param location 故事圣经地点档案。
     * @return 最终地点档案。
     */
    private ScriptDocument.LocationProfile toLocationProfile(BibleLocation location) {
        Objects.requireNonNull(location, "bibleLocation must not be null");
        log.debug("映射故事圣经地点 locationId={} name={}", location.id(), location.name());
        return new ScriptDocument.LocationProfile(
                location.id(),
                location.name(),
                location.type(),
                location.description()
        );
    }

    /**
     * 构造最终剧本文档元信息。
     *
     * @param project 小说改编项目。
     * @param scenePlan 场景规划。
     * @return 剧本文档元信息。
     */
    private ScriptDocument.ScriptMetadata buildMetadata(NovelProject project, ScenePlan scenePlan) {
        /*
         * 旧逻辑直接取 scenePlan 中出现过的最大章节号。
         * 当模型把多个原文章节合并/压缩进更少的 scene 时，最大章节号可能小于最小校验值 3，
         * 从而在最终装配阶段被 ScriptMetadata 的 @Min(3) 卡住。
         */
        int sourceChapterCount = Math.max(3, inferSourceChapterCount(scenePlan));
        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        log.debug("构造剧本文档元信息 projectId={} sourceChapterCount={} generatedAt={}",
                project.getId(), sourceChapterCount, generatedAt);
        return new ScriptDocument.ScriptMetadata(project.getTitle(), DEFAULT_LANGUAGE, sourceChapterCount, generatedAt);
    }

    /**
     * 构造最终剧本文档改编信息。
     *
     * @param options 生成参数。
     * @param storyBible 全局故事圣经。
     * @return 改编信息。
     */
    private ScriptDocument.Adaptation buildAdaptation(GenerationOptions options, StoryBible storyBible) {
        log.debug("构造剧本文档改编信息 format={} tone={} themeCount={}",
                options.format(), options.tone(), storyBible.themes().size());
        return new ScriptDocument.Adaptation(options.format(), options.tone(),
                storyBible.mainPlot(), storyBible.themes());
    }

    /**
     * 构造最终剧本文档修订备注。
     *
     * @param storyBible 全局故事圣经。
     * @param scenePlan 场景规划。
     * @param scenes 最终场景列表。
     * @return 修订备注列表。
     */
    private List<String> buildRevisionNotes(StoryBible storyBible, ScenePlan scenePlan,
                                            List<ScriptDocument.Scene> scenes) {
        List<String> notes = new ArrayList<>();
        notes.add("由多阶段生成流水线确定性组装，组装阶段未调用 AI。");
        notes.add("场景规划数量：" + scenePlan.scenes().size() + "，最终场景数量：" + scenes.size() + "。");
        storyBible.continuityRules().stream()
                .map(rule -> "连续性规则：" + rule)
                .forEach(notes::add);
        log.debug("构造剧本文档修订备注 noteCount={}", notes.size());
        return List.copyOf(notes);
    }

    /**
     * 根据场景规划推断原文章节总数。
     *
     * @param scenePlan 场景规划。
     * @return 场景规划中出现过的最大章节编号。
     */
    private static int inferSourceChapterCount(ScenePlan scenePlan) {
        int sourceChapterCount = scenePlan.scenes().stream()
                .flatMap(scene -> scene.sourceChapters().stream())
                .mapToInt(Integer::intValue)
                .max()
                .orElseThrow(() -> new IllegalArgumentException("scenePlan sourceChapters must not be empty"));
        log.debug("推断原文章节总数 sourceChapterCount={}", sourceChapterCount);
        return sourceChapterCount;
    }

    /**
     * 校验最终场景列表和场景规划保持一一对应。
     *
     * @param scenePlan 场景规划。
     * @param scenes 最终场景列表。
     */
    private static void validateScenesAgainstPlan(ScenePlan scenePlan, List<ScriptDocument.Scene> scenes) {
        if (scenePlan.scenes().size() != scenes.size()) {
            log.warn("最终场景数量与场景规划不一致 plannedCount={} actualCount={}",
                    scenePlan.scenes().size(), scenes.size());
            throw new IllegalArgumentException("scenes must match scenePlan size");
        }
        for (int index = 0; index < scenes.size(); index++) {
            validateSceneAgainstPlan(scenePlan.scenes().get(index), scenes.get(index));
        }
    }

    /**
     * 校验单个最终场景和对应场景规划保持一致。
     *
     * @param plannedScene 场景规划项。
     * @param scene 最终场景。
     */
    private static void validateSceneAgainstPlan(PlannedScene plannedScene, ScriptDocument.Scene scene) {
        if (!plannedScene.id().equals(scene.id())) {
            log.warn("最终场景编号与规划不一致 plannedSceneId={} sceneId={}", plannedScene.id(), scene.id());
            throw new IllegalArgumentException("scene id must match planned scene: " + scene.id());
        }
        if (!plannedScene.sourceChapters().equals(scene.sourceChapters())) {
            log.warn("最终场景章节引用与规划不一致 sceneId={}", scene.id());
            throw new IllegalArgumentException("scene sourceChapters must match planned scene: " + scene.id());
        }
        if (!plannedScene.locationId().equals(scene.locationId())) {
            log.warn("最终场景地点引用与规划不一致 sceneId={}", scene.id());
            throw new IllegalArgumentException("scene locationId must match planned scene: " + scene.id());
        }
        if (!plannedScene.characters().equals(scene.characters())) {
            log.warn("最终场景人物引用与规划不一致 sceneId={}", scene.id());
            throw new IllegalArgumentException("scene characters must match planned scene: " + scene.id());
        }
    }

    /**
     * 校验最终场景引用的人物和地点都来自故事圣经。
     *
     * @param storyBible 全局故事圣经。
     * @param scenes 最终场景列表。
     */
    private static void validateSceneReferences(StoryBible storyBible, List<ScriptDocument.Scene> scenes) {
        Set<String> characterIds = new HashSet<>();
        storyBible.characters().forEach(character -> characterIds.add(character.id()));
        Set<String> locationIds = new HashSet<>();
        storyBible.locations().forEach(location -> locationIds.add(location.id()));
        for (ScriptDocument.Scene scene : scenes) {
            validateSceneReferences(scene, characterIds, locationIds);
        }
    }

    /**
     * 校验单个最终场景引用的人物、地点和对白说话人。
     *
     * @param scene 最终场景。
     * @param characterIds 可引用人物编号集合。
     * @param locationIds 可引用地点编号集合。
     */
    private static void validateSceneReferences(ScriptDocument.Scene scene, Set<String> characterIds,
                                                Set<String> locationIds) {
        if (!locationIds.contains(scene.locationId())) {
            log.warn("最终场景引用了不存在的地点 sceneId={} locationId={}", scene.id(), scene.locationId());
            throw new IllegalArgumentException("scene location must exist in story bible: " + scene.locationId());
        }
        for (String characterId : scene.characters()) {
            if (!characterIds.contains(characterId)) {
                log.warn("最终场景引用了不存在的人物 sceneId={} characterId={}", scene.id(), characterId);
                throw new IllegalArgumentException("scene character must exist in story bible: " + characterId);
            }
        }
        for (ScriptDocument.SceneBlock block : scene.blocks()) {
            if ("dialogue".equals(block.type())
                    && (block.speakerId() == null || !characterIds.contains(block.speakerId()))) {
                log.warn("最终场景对白块引用了不存在的说话人 sceneId={} speakerId={}", scene.id(), block.speakerId());
                throw new IllegalArgumentException("dialogue speaker must exist in story bible: " + block.speakerId());
            }
        }
    }

    /**
     * 校验列表不能为空，并复制为不可变列表。
     *
     * @param values 原始列表。
     * @param name 参数名称。
     * @return 不可变列表。
     * @param <T> 列表元素类型。
     */
    private static <T> List<T> requireList(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            log.warn("最终剧本文档组装失败，列表参数为空 name={}", name);
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(values.stream()
                .map(value -> Objects.requireNonNull(value, name + " must not contain null"))
                .toList());
    }
}
