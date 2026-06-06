package com.novelplayer.application.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生成流水线阶段名称的集中定义。
 *
 * <p>阶段名称会写入数据库，因此不要在各个服务里手写字符串；后续新增阶段也应优先放在这里。</p>
 */
public final class GenerationStageNames {

    private static final Logger log = LoggerFactory.getLogger(GenerationStageNames.class);

    /**
     * 生成输入快照阶段。
     */
    public static final String GENERATION_INPUT = "generation_input";

    /**
     * 剧本生成管线调度阶段。
     */
    public static final String SCRIPT_GENERATION = "script_generation";

    /**
     * 旧的一次性剧本生成阶段。
     */
    public static final String LEGACY_SCRIPT_GENERATION = "legacy_script_generation";

    /**
     * 多阶段剧本生成调度阶段。
     */
    public static final String STAGED_SCRIPT_GENERATION = "staged_script_generation";

    /**
     * 章节摘要聚合阶段，用于前端展示整组章节摘要进度。
     */
    public static final String CHAPTER_DIGEST = "chapter_digest";

    /**
     * 故事圣经阶段。
     */
    public static final String STORY_BIBLE = "story_bible";

    /**
     * 场景规划阶段。
     */
    public static final String SCENE_PLAN = "scene_plan";

    /**
     * 分场草稿聚合阶段，用于前端展示整组分场生成进度。
     */
    public static final String SCENE_DRAFT = "scene_draft";

    /**
     * 最终剧本文档组装阶段。
     */
    public static final String SCRIPT_ASSEMBLY = "script_assembly";

    /**
     * 最终剧本文档 JSON 序列化阶段。
     */
    public static final String SERIALIZING_JSON = "serializing_json";

    /**
     * 最终 YAML 导出阶段。
     */
    public static final String EXPORTING_YAML = "exporting_yaml";

    /**
     * 最终剧本文档快照保存阶段。
     */
    public static final String SAVING_SNAPSHOT = "saving_snapshot";

    /**
     * 工具类不允许实例化。
     */
    private GenerationStageNames() {
    }

    /**
     * 构造章节摘要阶段名称。
     *
     * @param chapterIndex 章节序号，从 1 开始。
     * @return 章节摘要阶段名称，例如 {@code chapter_digest:1}。
     */
    public static String chapterDigest(int chapterIndex) {
        if (chapterIndex <= 0) {
            log.warn("生成章节摘要阶段名称失败，章节序号非法 chapterIndex={}", chapterIndex);
            throw new IllegalArgumentException("chapterIndex must be positive");
        }
        String stageName = "chapter_digest:" + chapterIndex;
        log.debug("生成章节摘要阶段名称 chapterIndex={} stageName={}", chapterIndex, stageName);
        return stageName;
    }

    /**
     * 构造分场草稿阶段名称。
     *
     * @param sceneId 场景编号。
     * @return 分场草稿阶段名称，例如 {@code scene_draft:scene_001}。
     */
    public static String sceneDraft(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) {
            log.warn("生成分场草稿阶段名称失败，场景编号为空 sceneId={}", sceneId);
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        String normalizedSceneId = sceneId.trim();
        String stageName = "scene_draft:" + normalizedSceneId;
        log.debug("生成分场草稿阶段名称 sceneId={} stageName={}", normalizedSceneId, stageName);
        return stageName;
    }
}
