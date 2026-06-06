package com.novelplayer.ai;

import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.CharacterMention;
import com.novelplayer.application.generation.model.DraftSceneBlock;
import com.novelplayer.application.generation.model.LocationMention;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 阶段化生成接口的本地模拟实现。
 *
 * <p>该实现不调用真实模型，输出稳定可预测的中间模型，用于先验证多阶段流水线、阶段落库和后续组装器设计。</p>
 */
@Component
@ConditionalOnProperty(prefix = "novel-player.generation", name = "mock-ai", havingValue = "true", matchIfMissing = true)
public class MockStagedScriptAiClient implements StagedScriptAiClient {

    private static final Logger log = LoggerFactory.getLogger(MockStagedScriptAiClient.class);

    /**
     * 生成单章模拟摘要。
     *
     * @param project 小说改编项目。
     * @param chapter 待分析章节。
     * @param options 生成参数。
     * @return 稳定的章节摘要中间模型。
     */
    @Override
    public ChapterDigest generateChapterDigest(NovelProject project, NovelChapter chapter, GenerationOptions options) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(chapter, "chapter must not be null");
        Objects.requireNonNull(options, "options must not be null");
        String summary = summarize(chapter.getContent(), 90);
        log.info("生成模拟章节摘要 projectId={} chapterIndex={} titleLength={} summaryLength={}",
                project.getId(), chapter.getChapterIndex(), chapter.getTitle().length(), summary.length());

        return new ChapterDigest(
                chapter.getChapterIndex(),
                chapter.getTitle(),
                summary,
                List.of("第 %d 章的核心事件被压缩为可改编素材".formatted(chapter.getChapterIndex())),
                List.of(
                        new CharacterMention("主角", List.of("她", "他"), "protagonist", "推动故事进入关键冲突"),
                        new CharacterMention("对手", List.of(), "antagonist", "制造阻力并隐藏信息")
                ),
                List.of(new LocationMention("核心场景", "interior", "从第 %d 章抽象出的主要冲突发生地".formatted(chapter.getChapterIndex()))),
                List.of("主角目标与阻力之间发生直接碰撞"),
                List.of("第 %d 章留下的关键悬念需要在后续场景中回应".formatted(chapter.getChapterIndex())),
                List.of("将章节叙事压缩为一个具有明确起承转合的场景")
        );
    }

    /**
     * 根据章节摘要生成模拟故事圣经。
     *
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要。
     * @param options 生成参数。
     * @return 稳定的故事圣经中间模型。
     */
    @Override
    public StoryBible generateStoryBible(NovelProject project, List<ChapterDigest> chapterDigests,
                                         GenerationOptions options) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<ChapterDigest> digests = requireNonEmpty(chapterDigests, "chapterDigests");
        log.info("生成模拟故事圣经 projectId={} chapterDigestCount={} format={} tone={}",
                project.getId(), digests.size(), options.format(), options.tone());

        return new StoryBible(
                List.of(
                        new BibleCharacter("char_001", "主角", List.of("她", "他"), "protagonist",
                                "在连续事件中寻找真相并做出选择", List.of("敏感", "克制", "行动力强"),
                                "短句为主，情绪压在动作里"),
                        new BibleCharacter("char_002", "对手", List.of(), "antagonist",
                                "隐藏真相并制造阻力", List.of("冷静", "强势"),
                                "语气平稳，常用反问")
                ),
                List.of(new BibleLocation("loc_001", "核心场景", "interior", "由章节摘要归并出的主要冲突发生地")),
                "《%s》的主角在 %d 个章节中连续发现线索，并被迫面对关系和真相的选择。"
                        .formatted(project.getTitle(), digests.size()),
                List.of("选择", "真相", "关系裂变"),
                List.of("主角必须随章节推进逐步接近真相", "对手不能在前期主动交代全部秘密")
        );
    }

    /**
     * 根据章节摘要和故事圣经生成模拟场景规划。
     *
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要。
     * @param storyBible 全局故事圣经。
     * @param options 生成参数。
     * @return 稳定的场景规划中间模型。
     */
    @Override
    public ScenePlan generateScenePlan(NovelProject project, List<ChapterDigest> chapterDigests, StoryBible storyBible,
                                       GenerationOptions options) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(storyBible, "storyBible must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<ChapterDigest> digests = requireNonEmpty(chapterDigests, "chapterDigests");
        String locationId = storyBible.locations().getFirst().id();
        List<String> characterIds = storyBible.characters().stream()
                .limit(2)
                .map(BibleCharacter::id)
                .toList();
        log.info("生成模拟场景规划 projectId={} digestCount={} characterCount={} locationCount={}",
                project.getId(), digests.size(), storyBible.characters().size(), storyBible.locations().size());

        List<PlannedScene> scenes = digests.stream()
                .map(digest -> new PlannedScene(
                        "scene_%03d".formatted(digest.chapterIndex()),
                        digest.title(),
                        List.of(digest.chapterIndex()),
                        locationId,
                        digest.chapterIndex() == 1 ? "night" : "day",
                        characterIds,
                        "将第 %d 章的主要冲突转化为可表演场面".formatted(digest.chapterIndex()),
                        digest.summary(),
                        digest.majorEvents().isEmpty()
                                ? List.of("建立场景目标", "制造人物阻力", "留下后续悬念")
                                : digest.majorEvents()
                ))
                .toList();
        log.debug("模拟场景规划完成 projectId={} sceneCount={}", project.getId(), scenes.size());
        return new ScenePlan(scenes);
    }

    /**
     * 根据单个场景大纲生成模拟分场草稿。
     *
     * @param project 小说改编项目。
     * @param plannedScene 待生成的场景大纲。
     * @param sourceChapters 场景关联的原文章节。
     * @param storyBible 全局故事圣经。
     * @param options 生成参数。
     * @return 稳定的分场草稿中间模型。
     */
    @Override
    public SceneDraft generateSceneDraft(NovelProject project, PlannedScene plannedScene,
                                         List<NovelChapter> sourceChapters, StoryBible storyBible,
                                         GenerationOptions options) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(plannedScene, "plannedScene must not be null");
        Objects.requireNonNull(storyBible, "storyBible must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<NovelChapter> chapters = requireNonEmpty(sourceChapters, "sourceChapters");
        String firstCharacterId = plannedScene.characters().getFirst();
        String secondCharacterId = plannedScene.characters().size() > 1 ? plannedScene.characters().get(1) : firstCharacterId;
        log.info("生成模拟分场草稿 projectId={} sceneId={} sourceChapterCount={} blockSeedLength={}",
                project.getId(), plannedScene.id(), chapters.size(), chapters.getFirst().getContent().length());

        return new SceneDraft(
                plannedScene.id(),
                plannedScene.title(),
                plannedScene.sourceChapters(),
                plannedScene.locationId(),
                plannedScene.timeOfDay(),
                plannedScene.characters(),
                plannedScene.dramaticPurpose(),
                plannedScene.summary(),
                List.of(
                        new DraftSceneBlock("action", null,
                                "场景从一个明确动作开始，人物被推入当前冲突。"),
                        new DraftSceneBlock("dialogue", firstCharacterId,
                                "这件事不能再拖了。"),
                        new DraftSceneBlock("dialogue", secondCharacterId,
                                "你确定自己知道真相吗？"),
                        new DraftSceneBlock("transition", null, "CUT TO:")
                )
        );
    }

    private static String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "当前章节缺少可用正文，模拟摘要保留为空白输入提示。";
        }
        String normalized = value.strip();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private static <T> List<T> requireNonEmpty(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(values);
    }
}
