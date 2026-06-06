package com.novelplayer.application.generation;

import com.novelplayer.ai.ScriptAiClient;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.script.ScriptSchemaValidator;
import com.novelplayer.config.NovelPlayerProperties;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 剧本生成的高层阶段化管线入口。
 *
 * <p>该管线不再一次性要求模型输出完整剧本，而是按章节摘要、故事圣经、场景规划、
 * 分场草稿和最终组装逐步生成。每个阶段都可以通过阶段结果表复用，降低长文本生成失败后的重试成本。</p>
 */
@Service
public class ScriptGenerationPipeline {

    private static final Logger log = LoggerFactory.getLogger(ScriptGenerationPipeline.class);

    private final NovelPlayerProperties properties;
    private final ScriptAiClient scriptAiClient;
    private final ObjectProvider<ChapterDigestGenerator> chapterDigestGeneratorProvider;
    private final ObjectProvider<StoryBibleGenerator> storyBibleGeneratorProvider;
    private final ObjectProvider<ScenePlanner> scenePlannerProvider;
    private final ObjectProvider<SceneDraftGenerator> sceneDraftGeneratorProvider;
    private final ObjectProvider<ScriptAssembler> scriptAssemblerProvider;
    private final ScriptSchemaValidator validator;
    private final ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider;

    /**
     * 注入旧生成客户端、阶段化生成组件提供器和最终结构校验器。
     *
     * @param properties 应用配置。
     * @param scriptAiClient 旧的一次性剧本生成客户端。
     * @param chapterDigestGeneratorProvider 章节摘要阶段生成器提供器。
     * @param storyBibleGeneratorProvider 故事圣经阶段生成器提供器。
     * @param scenePlannerProvider 场景规划阶段生成器提供器。
     * @param sceneDraftGeneratorProvider 分场草稿阶段生成器提供器。
     * @param scriptAssemblerProvider 最终剧本文档组装器提供器。
     * @param validator 剧本文档校验器。
     * @param lifecycleServiceProvider 生成任务生命周期服务提供器。
     */
    public ScriptGenerationPipeline(NovelPlayerProperties properties,
                                    ScriptAiClient scriptAiClient,
                                    ObjectProvider<ChapterDigestGenerator> chapterDigestGeneratorProvider,
                                    ObjectProvider<StoryBibleGenerator> storyBibleGeneratorProvider,
                                    ObjectProvider<ScenePlanner> scenePlannerProvider,
                                    ObjectProvider<SceneDraftGenerator> sceneDraftGeneratorProvider,
                                    ObjectProvider<ScriptAssembler> scriptAssemblerProvider,
                                    ScriptSchemaValidator validator,
                                    ObjectProvider<GenerationJobLifecycleService> lifecycleServiceProvider) {
        this.properties = properties;
        this.scriptAiClient = scriptAiClient;
        this.chapterDigestGeneratorProvider = chapterDigestGeneratorProvider;
        this.storyBibleGeneratorProvider = storyBibleGeneratorProvider;
        this.scenePlannerProvider = scenePlannerProvider;
        this.sceneDraftGeneratorProvider = sceneDraftGeneratorProvider;
        this.scriptAssemblerProvider = scriptAssemblerProvider;
        this.validator = validator;
        this.lifecycleServiceProvider = lifecycleServiceProvider;
    }

    /**
     * 根据配置选择旧链路或多阶段链路生成剧本文档，并立即做结构校验。
     *
     * @param job 当前生成任务，必须已经持久化。
     * @param project 小说改编项目。
     * @param chapters 已拆分并持久化的章节。
     * @param options 改编控制选项。
     * @return 已通过校验的剧本文档。
     */
    public ScriptDocument generate(GenerationJob job, NovelProject project, List<NovelChapter> chapters,
                                   GenerationOptions options) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(options, "options must not be null");
        List<NovelChapter> normalizedChapters = requireChapters(chapters);
        NovelPlayerProperties.Generation.PipelineMode pipelineMode = properties.getGeneration().getPipelineMode();
        log.info("剧本生成管线启动 jobId={} projectId={} chapterCount={} format={} tone={} pipelineMode={}",
                job.getId(), project.getId(), normalizedChapters.size(), options.format(), options.tone(), pipelineMode);

        if (pipelineMode == NovelPlayerProperties.Generation.PipelineMode.LEGACY) {
            return generateLegacy(job, project, normalizedChapters, options);
        }

//        组件缺失则
        if (!isStagedPipelineAvailable()) {
            log.warn("staged 管线已启用，但缺少 staged 组件，自动回退到 legacy 链路");
            return generateLegacy(job, project, normalizedChapters, options);
        }
        return generateStaged(job, project, normalizedChapters, options);
    }

    /**
     * 通过旧的一次性生成链路生成剧本文档。
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param chapters 已拆分并持久化的章节。
     * @param options 改编控制选项。
     * @return 已通过校验的剧本文档。
     */
    private ScriptDocument generateLegacy(GenerationJob job, NovelProject project, List<NovelChapter> chapters,
                                          GenerationOptions options) {
        moveToStage(job, GenerationStageNames.LEGACY_SCRIPT_GENERATION);
        log.info("使用旧的一次性剧本生成链路 jobId={} projectId={} chapterCount={}",
                job.getId(), project.getId(), chapters.size());
        ScriptDocument document = scriptAiClient.generateScript(project, chapters, options);
        log.info("旧生成链路返回剧本文档 jobId={} projectId={} schemaVersion={} sceneCount={}",
                job.getId(), project.getId(), document.schemaVersion(), document.scenes().size());
        validator.validate(document);
        log.info("旧生成链路剧本文档校验通过 jobId={} projectId={} schemaVersion={} sceneCount={}",
                job.getId(), project.getId(), document.schemaVersion(), document.scenes().size());
        return document;
    }

    /**
     * 通过多阶段生成链路生成剧本文档。
     *
     * @param job 当前生成任务。
     * @param project 小说改编项目。
     * @param chapters 已拆分并持久化的章节。
     * @param options 改编控制选项。
     * @return 已通过校验的剧本文档。
     */
    private ScriptDocument generateStaged(GenerationJob job, NovelProject project, List<NovelChapter> chapters,
                                          GenerationOptions options) {
        moveToStage(job, GenerationStageNames.CHAPTER_DIGEST);
        List<ChapterDigest> digests = requireStagedComponent(chapterDigestGeneratorProvider,
                "ChapterDigestGenerator").generate(job, project, chapters, options);
        log.info("章节摘要阶段完成 jobId={} projectId={} digestCount={}",
                job.getId(), project.getId(), digests.size());

        moveToStage(job, GenerationStageNames.STORY_BIBLE);
        StoryBible bible = requireStagedComponent(storyBibleGeneratorProvider,
                "StoryBibleGenerator").generate(job, project, digests, options);
        log.info("故事圣经阶段完成 jobId={} projectId={} characterCount={} locationCount={}",
                job.getId(), project.getId(), bible.characters().size(), bible.locations().size());

        moveToStage(job, GenerationStageNames.SCENE_PLAN);
        ScenePlan plan = requireStagedComponent(scenePlannerProvider,
                "ScenePlanner").plan(job, project, digests, bible, options);
        log.info("场景规划阶段完成 jobId={} projectId={} sceneCount={}",
                job.getId(), project.getId(), plan.scenes().size());

        moveToStage(job, GenerationStageNames.SCENE_DRAFT);
        List<SceneDraft> drafts = requireStagedComponent(sceneDraftGeneratorProvider,
                "SceneDraftGenerator").generate(job, project, chapters, plan, bible, options);
        log.info("分场草稿阶段完成 jobId={} projectId={} draftCount={}",
                job.getId(), project.getId(), drafts.size());

        moveToStage(job, GenerationStageNames.SCRIPT_ASSEMBLY);
        ScriptDocument document = requireStagedComponent(scriptAssemblerProvider,
                "ScriptAssembler").assembleDrafts(project, options, bible, plan, drafts);
        log.info("最终剧本文档组装完成 jobId={} projectId={} schemaVersion={} sceneCount={}",
                job.getId(), project.getId(), document.schemaVersion(), document.scenes().size());

        // 先校验再导出 YAML，避免无效角色/地点引用进入作者可下载结果。
        validator.validate(document);
        log.info("剧本文档结构校验通过 jobId={} projectId={} schemaVersion={} sceneCount={}",
                job.getId(), project.getId(), document.schemaVersion(), document.scenes().size());
        return document;
    }

//    新增方法
    private boolean isStagedPipelineAvailable() {
        return chapterDigestGeneratorProvider.getIfAvailable() != null
                && storyBibleGeneratorProvider.getIfAvailable() != null
                && scenePlannerProvider.getIfAvailable() != null
                && sceneDraftGeneratorProvider.getIfAvailable() != null
                && scriptAssemblerProvider.getIfAvailable() != null;
    }
    /**
     * 读取阶段化组件，缺失时给出明确配置错误。
     *
     * @param provider Spring 组件提供器。
     * @param componentName 组件名称。
     * @return 阶段化组件实例。
     * @param <T> 组件类型。
     */
    private static <T> T requireStagedComponent(ObjectProvider<T> provider, String componentName) {
        T component = provider.getIfAvailable();
        if (component == null) {
            throw new IllegalStateException(componentName
                    + " is required when novel-player.generation.pipeline-mode=staged");
        }
        return component;
    }

    /**
     * 更新当前任务阶段，并在异步执行时尽快提交到数据库。
     *
     * @param job 当前生成任务。
     * @param stage 阶段名称。
     */
    private void moveToStage(GenerationJob job, String stage) {
        job.moveToStage(stage);
        GenerationJobLifecycleService lifecycleService = lifecycleServiceProvider.getIfAvailable();
        if (lifecycleService != null && job.getId() != null) {
            lifecycleService.moveToStage(job.getId(), stage);
        }
    }

    /**
     * 校验章节列表，并复制为不可变列表。
     *
     * @param chapters 原文章节列表。
     * @return 不可变章节列表。
     */
    private static List<NovelChapter> requireChapters(List<NovelChapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("chapters must not be empty");
        }
        return List.copyOf(chapters.stream()
                .map(chapter -> Objects.requireNonNull(chapter, "chapters must not contain null"))
                .toList());
    }
}
