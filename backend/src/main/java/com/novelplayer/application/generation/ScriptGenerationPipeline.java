package com.novelplayer.application.generation;

import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.application.script.ScriptSchemaValidator;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final ChapterDigestGenerator chapterDigestGenerator;
    private final StoryBibleGenerator storyBibleGenerator;
    private final ScenePlanner scenePlanner;
    private final SceneDraftGenerator sceneDraftGenerator;
    private final ScriptAssembler scriptAssembler;
    private final ScriptSchemaValidator validator;

    /**
     * 注入阶段化生成组件和最终结构校验器。
     *
     * @param chapterDigestGenerator 章节摘要阶段生成器。
     * @param storyBibleGenerator 故事圣经阶段生成器。
     * @param scenePlanner 场景规划阶段生成器。
     * @param sceneDraftGenerator 分场草稿阶段生成器。
     * @param scriptAssembler 最终剧本文档组装器。
     * @param validator 剧本文档校验器。
     */
    public ScriptGenerationPipeline(ChapterDigestGenerator chapterDigestGenerator,
                                    StoryBibleGenerator storyBibleGenerator,
                                    ScenePlanner scenePlanner,
                                    SceneDraftGenerator sceneDraftGenerator,
                                    ScriptAssembler scriptAssembler,
                                    ScriptSchemaValidator validator) {
        this.chapterDigestGenerator = chapterDigestGenerator;
        this.storyBibleGenerator = storyBibleGenerator;
        this.scenePlanner = scenePlanner;
        this.sceneDraftGenerator = sceneDraftGenerator;
        this.scriptAssembler = scriptAssembler;
        this.validator = validator;
    }

    /**
     * 通过多阶段流水线生成剧本文档并立即做结构校验。
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
        log.info("阶段化剧本生成管线启动 jobId={} projectId={} chapterCount={} format={} tone={}",
                job.getId(), project.getId(), normalizedChapters.size(), options.format(), options.tone());

        job.moveToStage("chapter_digest");
        List<ChapterDigest> digests = chapterDigestGenerator.generate(job, project, normalizedChapters, options);
        log.info("章节摘要阶段完成 jobId={} projectId={} digestCount={}",
                job.getId(), project.getId(), digests.size());

        job.moveToStage(GenerationStageNames.STORY_BIBLE);
        StoryBible bible = storyBibleGenerator.generate(job, project, digests, options);
        log.info("故事圣经阶段完成 jobId={} projectId={} characterCount={} locationCount={}",
                job.getId(), project.getId(), bible.characters().size(), bible.locations().size());

        job.moveToStage(GenerationStageNames.SCENE_PLAN);
        ScenePlan plan = scenePlanner.plan(job, project, digests, bible, options);
        log.info("场景规划阶段完成 jobId={} projectId={} sceneCount={}",
                job.getId(), project.getId(), plan.scenes().size());

        job.moveToStage("scene_draft");
        List<SceneDraft> drafts = sceneDraftGenerator.generate(job, project, normalizedChapters, plan, bible, options);
        log.info("分场草稿阶段完成 jobId={} projectId={} draftCount={}",
                job.getId(), project.getId(), drafts.size());

        job.moveToStage(GenerationStageNames.SCRIPT_ASSEMBLY);
        ScriptDocument document = scriptAssembler.assembleDrafts(project, options, bible, plan, drafts);
        log.info("最终剧本文档组装完成 jobId={} projectId={} schemaVersion={} sceneCount={}",
                job.getId(), project.getId(), document.schemaVersion(), document.scenes().size());

        // 先校验再导出 YAML，避免无效角色/地点引用进入作者可下载结果。
        validator.validate(document);
        log.info("剧本文档结构校验通过 jobId={} projectId={} schemaVersion={} sceneCount={}",
                job.getId(), project.getId(), document.schemaVersion(), document.scenes().size());
        return document;
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
