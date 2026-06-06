package com.novelplayer.ai;

import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.application.generation.model.ChapterDigest;
import com.novelplayer.application.generation.model.PlannedScene;
import com.novelplayer.application.generation.model.SceneDraft;
import com.novelplayer.application.generation.model.SceneDraftContext;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.generation.model.StoryBible;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;

import java.util.List;

/**
 * 阶段化剧本生成 AI 客户端。
 *
 * <p>该接口面向长篇小说改编的新流水线，按章节摘要、故事圣经、场景规划和分场草稿拆分模型能力。
 * 旧的 {@link ScriptAiClient} 暂时保留，用于维持当前一次性生成链路。</p>
 */
public interface StagedScriptAiClient {

    /**
     * 根据单个章节生成结构化章节摘要。
     *
     * @param project 小说改编项目。
     * @param chapter 待分析章节。
     * @param options 生成参数。
     * @return 章节摘要中间模型。
     */
    ChapterDigest generateChapterDigest(NovelProject project, NovelChapter chapter, GenerationOptions options);

    /**
     * 根据所有章节摘要生成全局故事圣经。
     *
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要。
     * @param options 生成参数。
     * @return 故事圣经中间模型。
     */
    StoryBible generateStoryBible(NovelProject project, List<ChapterDigest> chapterDigests, GenerationOptions options);

    /**
     * 根据章节摘要和故事圣经生成场景规划。
     *
     * @param project 小说改编项目。
     * @param chapterDigests 按章节顺序排列的章节摘要。
     * @param storyBible 全局故事圣经。
     * @param options 生成参数。
     * @return 场景规划中间模型。
     */
    ScenePlan generateScenePlan(NovelProject project, List<ChapterDigest> chapterDigests, StoryBible storyBible,
                                GenerationOptions options);

    /**
     * 根据单个场景规划生成分场草稿。
     *
     * @param project 小说改编项目。
     * @param context 分场写作最小上下文。
     * @param options 生成参数。
     * @return 分场草稿中间模型。
     */
    SceneDraft generateSceneDraft(NovelProject project, SceneDraftContext context, GenerationOptions options);
}
