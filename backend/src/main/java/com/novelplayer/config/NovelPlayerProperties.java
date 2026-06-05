package com.novelplayer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "novel-player")
/**
 * 将应用配置中的 novel-player 配置绑定为类型安全的 Java 对象。
 */
public class NovelPlayerProperties {

    private Generation generation = new Generation();

    public Generation getGeneration() {
        return generation;
    }

    public void setGeneration(Generation generation) {
        this.generation = generation;
    }

    public static class Generation {

        /**
         * 题目要求：输入小说至少包含 3 个章节。
         */
        private int minimumChapters = 3;

        /**
         * 默认使用模拟生成，保证没有外部模型密钥时也能跑通完整产品流程。
         */
        private boolean mockAi = true;

        public int getMinimumChapters() {
            return minimumChapters;
        }

        public void setMinimumChapters(int minimumChapters) {
            this.minimumChapters = minimumChapters;
        }

        public boolean isMockAi() {
            return mockAi;
        }

        public void setMockAi(boolean mockAi) {
            this.mockAi = mockAi;
        }
    }
}
