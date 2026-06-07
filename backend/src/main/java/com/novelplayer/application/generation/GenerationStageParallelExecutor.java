package com.novelplayer.application.generation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 阶段内有界并行执行器。
 *
 * <p>它只负责并行调度、异常传播和结果顺序恢复，不包含任何小说或剧本业务规则。这样章节摘要、
 * 分场草稿等阶段可以共享同一套并发控制，避免每个生成器重复管理线程。</p>
 */
@Component
public class GenerationStageParallelExecutor {

    private final Executor executor;

    /**
     * 创建阶段内并行执行器。
     *
     * @param executor Spring 管理的阶段内任务线程池。
     */
    public GenerationStageParallelExecutor(@Qualifier("generationStageTaskExecutor") Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * 对输入列表做有界并行处理，并按输入顺序返回结果。
     *
     * <p>该方法最多同时提交 {@code maxConcurrency} 个任务；某个任务失败后不再提交新任务，并取消尚未完成的
     * Future。已经进入模型调用的任务不一定能被底层 HTTP 客户端立即中断，这是 Java Future 取消语义的正常限制。</p>
     *
     * @param stageLabel 日志和异常中使用的阶段标签。
     * @param inputs 待处理输入，返回结果顺序与该列表一致。
     * @param maxConcurrency 最大并发数，必须大于 0。
     * @param task 单个输入的处理函数。
     * @param <T> 输入类型。
     * @param <R> 输出类型。
     * @return 与输入顺序一致的结果列表。
     */
    public <T, R> List<R> runOrdered(String stageLabel, List<T> inputs, int maxConcurrency,
                                     Function<T, R> task) {
        Objects.requireNonNull(stageLabel, "stageLabel must not be null");
        Objects.requireNonNull(task, "task must not be null");
        List<T> normalizedInputs = List.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        if (normalizedInputs.isEmpty()) {
            return List.of();
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }

        int workerCount = Math.min(maxConcurrency, normalizedInputs.size());
        if (workerCount == 1) {
            return runSerial(normalizedInputs, task);
        }

        ExecutorCompletionService<IndexedResult<R>> completionService = new ExecutorCompletionService<>(executor);
        List<Future<IndexedResult<R>>> futures = new ArrayList<>(workerCount);
        Object[] orderedResults = new Object[normalizedInputs.size()];
        int submitted = 0;
        int completed = 0;

        try {
            while (submitted < workerCount) {
                futures.add(submit(completionService, normalizedInputs, submitted, task));
                submitted++;
            }
            while (completed < submitted) {
                Future<IndexedResult<R>> future = completionService.take();
                completed++;
                IndexedResult<R> result = future.get();
                orderedResults[result.index()] = result.value();
                if (submitted < normalizedInputs.size()) {
                    futures.add(submit(completionService, normalizedInputs, submitted, task));
                    submitted++;
                }
            }
        } catch (InterruptedException exception) {
            cancel(futures);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for parallel stage: " + stageLabel, exception);
        } catch (ExecutionException exception) {
            cancel(futures);
            throw asRuntimeException(stageLabel, exception.getCause());
        } catch (CancellationException exception) {
            cancel(futures);
            throw new IllegalStateException("Parallel stage was cancelled: " + stageLabel, exception);
        } catch (RuntimeException exception) {
            cancel(futures);
            throw exception;
        }

        List<R> ordered = new ArrayList<>(orderedResults.length);
        for (Object orderedResult : orderedResults) {
            @SuppressWarnings("unchecked")
            R value = (R) orderedResult;
            ordered.add(value);
        }
        return List.copyOf(ordered);
    }

    private static <T, R> Future<IndexedResult<R>> submit(
            ExecutorCompletionService<IndexedResult<R>> completionService,
            List<T> inputs,
            int index,
            Function<T, R> task
    ) {
        return completionService.submit(() -> new IndexedResult<>(index, task.apply(inputs.get(index))));
    }

    private static <T, R> List<R> runSerial(List<T> inputs, Function<T, R> task) {
        List<R> results = new ArrayList<>(inputs.size());
        for (T input : inputs) {
            results.add(task.apply(input));
        }
        return List.copyOf(results);
    }

    private static void cancel(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static RuntimeException asRuntimeException(String stageLabel, Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Parallel stage failed: " + stageLabel, throwable);
    }

    private record IndexedResult<R>(int index, R value) {
    }
}
