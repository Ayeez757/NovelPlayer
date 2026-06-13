package com.novelplayer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 将应用配置中的 {@code novel-player} 配置绑定为类型安全的 Java 对象。
 */
@ConfigurationProperties(prefix = "novel-player")
public class NovelPlayerProperties {

    private Ai ai = new Ai();

    private Generation generation = new Generation();

    /**
     * 读取 AI 供应商相关配置。
     *
     * @return AI 供应商配置。
     */
    public Ai getAi() {
        return ai;
    }

    /**
     * 设置 AI 供应商相关配置。
     *
     * @param ai AI 供应商配置。
     */
    public void setAi(Ai ai) {
        this.ai = ai == null ? new Ai() : ai;
    }

    /**
     * 读取生成相关配置。
     *
     * @return 生成配置分组。
     */
    public Generation getGeneration() {
        return generation;
    }

    /**
     * 设置生成相关配置。
     *
     * @param generation 生成配置分组。
     */
    public void setGeneration(Generation generation) {
        this.generation = generation == null ? new Generation() : generation;
    }

    /**
     * AI 供应商配置分组。
     */
    public static class Ai {

        private DeepSeek deepseek = new DeepSeek();

        /**
         * 读取 DeepSeek 专用配置。
         *
         * @return DeepSeek 配置。
         */
        public DeepSeek getDeepseek() {
            return deepseek;
        }

        /**
         * 设置 DeepSeek 专用配置。
         *
         * @param deepseek DeepSeek 配置。
         */
        public void setDeepseek(DeepSeek deepseek) {
            this.deepseek = deepseek == null ? new DeepSeek() : deepseek;
        }
    }

    /**
     * DeepSeek 供应商专用配置。
     */
    public static class DeepSeek {

        /**
         * DeepSeek V4 thinking 模式。
         *
         * <p>该字段会被转换为 DeepSeek 的 OpenAI-compatible 扩展参数
         * {@code thinking: {type: "disabled"}}。它不是通用 OpenAI 标准字段，因此只在
         * DeepSeek 客户端中使用，避免将来接入其他模型时请求体不兼容。</p>
         */
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;

        /**
         * 读取 DeepSeek thinking 模式。
         *
         * @return thinking 模式。
         */
        public ThinkingMode getThinkingMode() {
            return thinkingMode;
        }

        /**
         * 设置 DeepSeek thinking 模式。
         *
         * @param thinkingMode thinking 模式。
         */
        public void setThinkingMode(ThinkingMode thinkingMode) {
            this.thinkingMode = thinkingMode == null ? ThinkingMode.DEFAULT : thinkingMode;
        }

        /**
         * DeepSeek thinking 模式。
         */
        public enum ThinkingMode {
            /**
             * 不注入 thinking 字段，保留模型服务端默认行为。
             */
            DEFAULT,

            /**
             * 注入 {@code thinking: {type: "disabled"}}，关闭思考模式以降低延迟。
             */
            DISABLED
        }
    }

    /**
     * 生成流程配置分组。
     */
    public static class Generation {

        /**
         * 题目要求：输入小说至少包含 3 个章节。
         */
        private int minimumChapters = 3;

        /**
         * 默认使用模拟生成，保证没有外部模型密钥时也能跑通完整产品流程。
         */
        private boolean mockAi = true;

        /**
         * 生成管线模式。默认启用多阶段生成，必要时可切回旧的一次性生成链路。
         */
        private PipelineMode pipelineMode = PipelineMode.STAGED;

        /**
         * 章节摘要阶段最大并发数。
         *
         * <p>单章摘要之间没有内容依赖，适合使用有界并行缩短长篇小说总耗时。默认值保持保守，
         * 避免同时打满模型限流或数据库连接池。</p>
         */
        private int chapterDigestConcurrency = 4;

        /**
         * 分场草稿阶段最大并发数。
         *
         * <p>值为 1 时保持严格串行，并把上一场实际生成的摘要传给下一场；值大于 1 时改用上一场
         * 场景规划摘要作为邻近上下文，从而允许有界并行。</p>
         */
        private int sceneDraftConcurrency = 2;

        /**
         * 读取生成前要求的最小章节数。
         *
         * @return 最小章节数。
         */
        public int getMinimumChapters() {
            return minimumChapters;
        }

        /**
         * 设置生成前要求的最小章节数。
         *
         * @param minimumChapters 最小章节数。
         */
        public void setMinimumChapters(int minimumChapters) {
            this.minimumChapters = minimumChapters;
        }

        /**
         * 判断当前是否使用本地模拟 AI。
         *
         * @return true 表示使用 mock 实现。
         */
        public boolean isMockAi() {
            return mockAi;
        }

        /**
         * 设置是否使用本地模拟 AI。
         *
         * @param mockAi true 表示注册 mock AI 客户端。
         */
        public void setMockAi(boolean mockAi) {
            this.mockAi = mockAi;
        }

        /**
         * 读取当前生成管线模式。
         *
         * @return 生成管线模式。
         */
        public PipelineMode getPipelineMode() {
            return pipelineMode;
        }

        /**
         * 设置生成管线模式。
         *
         * @param pipelineMode 生成管线模式。
         */
        public void setPipelineMode(PipelineMode pipelineMode) {
            this.pipelineMode = pipelineMode == null ? PipelineMode.STAGED : pipelineMode;
        }

        /**
         * 读取章节摘要阶段最大并发数。
         *
         * @return 章节摘要并发数。
         */
        public int getChapterDigestConcurrency() {
            return chapterDigestConcurrency;
        }

        /**
         * 设置章节摘要阶段最大并发数。
         *
         * @param chapterDigestConcurrency 章节摘要并发数。
         */
        public void setChapterDigestConcurrency(int chapterDigestConcurrency) {
            this.chapterDigestConcurrency = requireConcurrency(
                    chapterDigestConcurrency, "chapterDigestConcurrency");
        }

        /**
         * 读取分场草稿阶段最大并发数。
         *
         * @return 分场草稿并发数。
         */
        public int getSceneDraftConcurrency() {
            return sceneDraftConcurrency;
        }

        /**
         * 设置分场草稿阶段最大并发数。
         *
         * @param sceneDraftConcurrency 分场草稿并发数。
         */
        public void setSceneDraftConcurrency(int sceneDraftConcurrency) {
            this.sceneDraftConcurrency = requireConcurrency(sceneDraftConcurrency, "sceneDraftConcurrency");
        }

        /**
         * 限制阶段并发数，避免错误配置触发过多模型请求或挤压数据库连接。
         *
         * @param value 原始并发数。
         * @param name 配置名称。
         * @return 已校验的并发数。
         */
        private static int requireConcurrency(int value, String name) {
            if (value < 1 || value > 16) {
                throw new IllegalArgumentException(name + " must be between 1 and 16");
            }
            return value;
        }

        /**
         * 生成管线模式枚举。
         */
        public enum PipelineMode {
            /**
             * 旧的一次性剧本生成链路。
             */
            LEGACY,

            /**
             * 新的章节摘要、故事圣经、场景规划、分场草稿和最终组装链路。
             */
            STAGED
        }
    }
}
