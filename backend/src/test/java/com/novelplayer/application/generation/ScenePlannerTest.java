package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.ai.LlmJsonClient;
import com.novelplayer.application.generation.model.BibleCharacter;
import com.novelplayer.application.generation.model.BibleLocation;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.generation.prompt.ScenePlanPromptBuilder;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.generation.GenerationStageResult;
import com.novelplayer.domain.generation.GenerationStatus;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.infra.repository.GenerationStageResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖场景规划阶段生成器的缓存复用、成功保存和跨阶段引用校验。
 */
@ExtendWith(MockitoExtension.class)
class ScenePlannerTest {

    @Mock
    private LlmJsonClient llmJsonClient;

    @Mock
    private GenerationStageResultRepository repository;

    private GenerationStageStore stageStore;
    private ScenePlanner planner;

    /**
     * 为每个用例创建阶段存储和场景规划器。
     */
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        stageStore = new GenerationStageStore(repository, objectMapper);
        planner = new ScenePlanner(
                llmJsonClient,
                objectMapper,
                stageStore,
                new ScenePlanPromptBuilder("SYSTEM", "INPUT:\n%s")
        );
    }

    /**
     * 验证输入哈希命中时会复用已保存的场景规划。
     */
    @Test
    void reusesCachedScenePlanWhenInputHashMatches() {
        GenerationJob job = persistedJob(12L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest(1));
        StoryBible bible = sampleBible();
        ScenePlan scenePlan = sampleScenePlan();
        String inputHash = stageStore.sha256OfJson(scenePlanInput(project, digests, bible, options));
        GenerationStageResult cached = new GenerationStageResult(
                job,
                GenerationStageNames.SCENE_PLAN,
                GenerationStatus.SUCCEEDED,
                inputHash,
                toJson(scenePlan),
                null
        );
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                12L, GenerationStageNames.SCENE_PLAN, GenerationStatus.SUCCEEDED, inputHash))
                .thenReturn(Optional.of(cached));

        ScenePlan result = planner.plan(job, project, digests, bible, options);

        assertThat(result).isEqualTo(scenePlan);
        verify(llmJsonClient, never()).requestJson(any(), any(), any());
    }

    /**
     * 验证缓存未命中时会生成、校验并保存场景规划。
     */
    @Test
    void generatesValidScenePlanAndPersistsItWhenCacheMisses() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(21L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = new GenerationOptions("web_drama", "suspense", 60, 30, "压缩为高密度短剧结构");
        List<ChapterDigest> digests = List.of(sampleDigest(1));
        StoryBible bible = sampleBible();
        ScenePlan scenePlan = sampleScenePlan();
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(21L), eq(GenerationStageNames.SCENE_PLAN), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.SCENE_PLAN),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(scenePlan));

        ScenePlan result = planner.plan(job, project, digests, bible, options);

        assertThat(result).isEqualTo(scenePlan);
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        GenerationStageResult saved = captor.getValue();
        assertThat(saved.getStageName()).isEqualTo(GenerationStageNames.SCENE_PLAN);
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(saved.getOutputJson()).contains("\"id\":\"scene_001\"");
    }

    /**
     * 验证场景引用不存在的原文章节时会记录失败阶段。
     */
    @Test
    void recordsFailedStageWhenSceneReferencesMissingChapter() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(31L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest(1));
        StoryBible bible = sampleBible();
        ScenePlan invalidPlan = new ScenePlan(List.of(plannedScene(
                "scene_001",
                List.of(2),
                "loc_001",
                List.of("char_001")
        )));
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(31L), eq(GenerationStageNames.SCENE_PLAN), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.SCENE_PLAN),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(invalidPlan));

        assertThatThrownBy(() -> planner.plan(job, project, digests, bible, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scene_001")
                .hasMessageContaining("2");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(captor.getValue().getStageName()).isEqualTo(GenerationStageNames.SCENE_PLAN);
    }

    /**
     * 验证场景引用不存在的人物时会记录失败阶段。
     */
    @Test
    void recordsFailedStageWhenSceneReferencesUnknownCharacter() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(41L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest(1));
        StoryBible bible = sampleBible();
        ScenePlan invalidPlan = new ScenePlan(List.of(plannedScene(
                "scene_001",
                List.of(1),
                "loc_001",
                List.of("char_999")
        )));
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(41L), eq(GenerationStageNames.SCENE_PLAN), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.SCENE_PLAN),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(invalidPlan));

        assertThatThrownBy(() -> planner.plan(job, project, digests, bible, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("char_999");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    /**
     * 验证场景引用不存在的地点时会记录失败阶段。
     */
    @Test
    void recordsFailedStageWhenSceneReferencesUnknownLocation() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(51L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest(1));
        StoryBible bible = sampleBible();
        ScenePlan invalidPlan = new ScenePlan(List.of(plannedScene(
                "scene_001",
                List.of(1),
                "loc_999",
                List.of("char_001")
        )));
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(51L), eq(GenerationStageNames.SCENE_PLAN), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.SCENE_PLAN),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(invalidPlan));

        assertThatThrownBy(() -> planner.plan(job, project, digests, bible, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loc_999");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    /**
     * 验证重复场景 ID 会被规划阶段校验拦截。
     */
    @Test
    void recordsFailedStageWhenSceneIdIsDuplicated() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        GenerationJob job = persistedJob(61L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");
        GenerationOptions options = GenerationOptions.defaults();
        List<ChapterDigest> digests = List.of(sampleDigest(1), sampleDigest(2));
        StoryBible bible = sampleBible();
        ScenePlan invalidPlan = new ScenePlan(List.of(
                plannedScene("scene_001", List.of(1), "loc_001", List.of("char_001")),
                plannedScene("scene_001", List.of(2), "loc_001", List.of("char_002"))
        ));
        when(repository.findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                eq(61L), eq(GenerationStageNames.SCENE_PLAN), eq(GenerationStatus.SUCCEEDED), any(String.class)))
                .thenReturn(Optional.empty());
        when(llmJsonClient.requestJson(
                eq(GenerationStageNames.SCENE_PLAN),
                eq("SYSTEM"),
                any(String.class)))
                .thenReturn(toJson(invalidPlan));

        assertThatThrownBy(() -> planner.plan(job, project, digests, bible, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scene_001");
        ArgumentCaptor<GenerationStageResult> captor = ArgumentCaptor.forClass(GenerationStageResult.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    /**
     * 验证空章节摘要列表会在阶段查询前被拒绝。
     */
    @Test
    void rejectsEmptyDigestListBeforeStageLookup() {
        GenerationJob job = persistedJob(71L);
        NovelProject project = new NovelProject("雨夜", "第一章 雨夜\n她发现一封信。");

        assertThatThrownBy(() -> planner.plan(job, project, List.of(), sampleBible(), GenerationOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findFirstByJobIdAndStageNameAndStatusAndInputHashOrderByCreatedAtDesc(
                any(), any(), any(), any());
    }

    /**
     * 构造场景规划测试用的章节摘要。
     *
     * @param chapterIndex 章节序号。
     * @return 章节摘要样例。
     */
    private static ChapterDigest sampleDigest(int chapterIndex) {
        return new ChapterDigest(
                chapterIndex,
                "第%d章".formatted(chapterIndex),
                "林安在第%d章发现新的线索。".formatted(chapterIndex),
                List.of("发现线索"),
                List.of(),
                List.of(),
                List.of("她必须判断线索真假"),
                List.of("线索背后的人是谁"),
                List.of("压缩为一场明确目标的调查戏")
        );
    }

    /**
     * 构造满足人物和地点引用约束的故事圣经样例。
     *
     * @return 故事圣经样例。
     */
    private static StoryBible sampleBible() {
        return new StoryBible(
                List.of(
                        new BibleCharacter("char_001", "林安", List.of("她"), "protagonist",
                                "寻找父亲失踪真相", List.of("敏感", "克制"), "短句为主"),
                        new BibleCharacter("char_002", "店主", List.of(), "supporting",
                                "隐藏部分真相", List.of("谨慎"), "语气平稳")
                ),
                List.of(new BibleLocation("loc_001", "旧书店", "interior", "昏暗狭窄")),
                "林安寻找父亲失踪真相。",
                List.of("真相", "选择"),
                List.of("第七章前不能揭露父亲身份")
        );
    }

    /**
     * 构造有效的场景规划样例。
     *
     * @return 场景规划样例。
     */
    private static ScenePlan sampleScenePlan() {
        return new ScenePlan(List.of(plannedScene("scene_001", List.of(1), "loc_001",
                List.of("char_001", "char_002"))));
    }

    /**
     * 构造可按参数变体调整引用关系的场景大纲。
     *
     * @param id 场景编号。
     * @param sourceChapters 原文章节引用。
     * @param locationId 地点编号。
     * @param characters 人物编号列表。
     * @return 场景大纲样例。
     */
    private static PlannedScene plannedScene(String id, List<Integer> sourceChapters, String locationId,
                                             List<String> characters) {
        return new PlannedScene(
                id,
                "旧书店试探",
                sourceChapters,
                locationId,
                "night",
                characters,
                "让主角第一次主动逼近真相",
                "林安在旧书店追问店主，发现信件背后的矛盾。",
                List.of("建立调查目标", "店主回避关键问题", "留下父亲失踪悬念")
        );
    }

    /**
     * 构造带主键的生成任务，模拟已持久化状态。
     *
     * @param id 任务主键。
     * @return 已设置主键的生成任务。
     */
    private static GenerationJob persistedJob(Long id) {
        GenerationJob job = new GenerationJob(new NovelProject("title", "source"));
        try {
            Field field = GenerationJob.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(job, id);
            return job;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to set job id for test", exception);
        }
    }

    /**
     * 将场景规划转为 JSON，便于模拟缓存命中的阶段输出。
     *
     * @param scenePlan 场景规划。
     * @return JSON 文本。
     */
    private static String toJson(ScenePlan scenePlan) {
        try {
            return new ObjectMapper().writeValueAsString(scenePlan);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 构造与生产代码一致的场景规划输入快照，用于计算预期缓存哈希。
     *
     * @param project 小说改编项目。
     * @param chapterDigests 章节摘要列表。
     * @param storyBible 故事圣经。
     * @param options 生成参数。
     * @return 哈希输入快照。
     */
    private static ScenePlanInput scenePlanInput(NovelProject project, List<ChapterDigest> chapterDigests,
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

    /**
     * 场景规划测试使用的输入快照结构。
     *
     * @param projectId 项目主键。
     * @param projectTitle 项目标题。
     * @param chapterDigests 章节摘要列表。
     * @param storyBible 故事圣经。
     * @param format 剧本形式。
     * @param tone 整体风格。
     * @param dialogueDensity 对白密度。
     * @param narrationRetention 旁白保留度。
     * @param hasAdditionalInstructions 是否包含补充要求。
     * @param additionalInstructions 补充要求。
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
    }
}
