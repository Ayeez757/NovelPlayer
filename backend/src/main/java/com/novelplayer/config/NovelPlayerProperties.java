package com.novelplayer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 将应用配置中的 novel-player 配置绑定为类型安全的 Java 对象。
 */
@ConfigurationProperties(prefix = "novel-player")
public class NovelPlayerProperties {

    private Generation generation = new Generation();

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
        this.generation = generation;
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
    }
}
