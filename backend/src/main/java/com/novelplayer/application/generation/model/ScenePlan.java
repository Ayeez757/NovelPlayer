package com.novelplayer.application.generation.model;

import java.util.List;

/**
 * 场景规划阶段的整体输出。
 *
 * <p>它描述小说章节如何被拆分、合并和排列为剧本场景，是分场写作前的结构蓝图。</p>
 *
 * @param scenes 按剧本顺序排列的场景大纲列表。
 */
public record ScenePlan(
        List<PlannedScene> scenes
) {

    /**
     * 创建场景规划，并要求至少包含一个场景。
     */
    public ScenePlan {
        scenes = GenerationModelValidation.requireList(scenes, "scenes");
    }
}
