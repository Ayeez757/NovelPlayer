package com.novelplayer.application.generation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the shared bounded parallel runner used by staged generation phases.
 */
class GenerationStageParallelExecutorTest {

    @Test
    void runOrderedKeepsInputOrderWhenTasksCompleteOutOfOrder() {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            GenerationStageParallelExecutor executor = new GenerationStageParallelExecutor(executorService);
            CountDownLatch secondTaskStarted = new CountDownLatch(1);

            List<String> results = executor.runOrdered("test-stage", List.of(1, 2), 2, value -> {
                if (value == 1) {
                    await(secondTaskStarted);
                    return "first";
                }
                secondTaskStarted.countDown();
                return "second";
            });

            assertThat(results).containsExactly("first", "second");
        } finally {
            executorService.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
