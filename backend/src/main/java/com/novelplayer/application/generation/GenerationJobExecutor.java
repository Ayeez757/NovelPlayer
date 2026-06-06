package com.novelplayer.application.generation;

import com.novelplayer.application.script.ScriptJsonMapper;
import com.novelplayer.application.script.YamlExporter;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import com.novelplayer.infra.repository.GenerationJobRepository;
import com.novelplayer.infra.repository.NovelChapterRepository;
import com.novelplayer.infra.repository.NovelProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 生成任务后台执行器。
 *
 * <p>该服务承接异步线程中的耗时生成流程。HTTP 请求只负责创建任务并立即返回，
 * 后台线程负责加载输入、执行管线、导出 YAML、保存文档并更新任务状态。</p>
 */
@Service
public class GenerationJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobExecutor.class);

    private final GenerationJobRepository jobRepository;
    private final NovelProjectRepository projectRepository;
    private final NovelChapterRepository chapterRepository;
    private final ScriptGenerationPipeline pipeline;
    private final GenerationInputSnapshotRecorder inputSnapshotRecorder;
    private final ScriptJsonMapper scriptJsonMapper;
    private final YamlExporter yamlExporter;
    private final GenerationJobLifecycleService lifecycleService;

    /**
     * 注入后台生成需要的仓储和应用服务。
     *
     * @param jobRepository 生成任务仓储。
     * @param projectRepository 项目仓储。
     * @param chapterRepository 章节仓储。
     * @param pipeline 剧本生成管线。
     * @param inputSnapshotRecorder 生成输入快照记录器。
     * @param scriptJsonMapper 剧本文档 JSON 映射器。
     * @param yamlExporter YAML 导出器。
     * @param lifecycleService 生成任务生命周期服务。
     */
    public GenerationJobExecutor(GenerationJobRepository jobRepository,
                                 NovelProjectRepository projectRepository,
                                 NovelChapterRepository chapterRepository,
                                 ScriptGenerationPipeline pipeline,
                                 GenerationInputSnapshotRecorder inputSnapshotRecorder,
                                 ScriptJsonMapper scriptJsonMapper,
                                 YamlExporter yamlExporter,
                                 GenerationJobLifecycleService lifecycleService) {
        this.jobRepository = jobRepository;
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.pipeline = pipeline;
        this.inputSnapshotRecorder = inputSnapshotRecorder;
        this.scriptJsonMapper = scriptJsonMapper;
        this.yamlExporter = yamlExporter;
        this.lifecycleService = lifecycleService;
    }

    /**
     * 异步执行已创建的生成任务。
     *
     * @param jobId 生成任务主键。
     * @param options 生成参数。
     */
    @Async("generationTaskExecutor")
    public void execute(Long jobId, GenerationOptions options) {
        log.info("异步生成任务开始调度 jobId={} format={} tone={}",
                jobId, options.format(), options.tone());
        try {
            run(jobId, options);
        } catch (RuntimeException exception) {
            log.warn("异步生成任务执行失败 jobId={} error={}", jobId, exception.getMessage(), exception);
            lifecycleService.markFailed(jobId, exception.getMessage());
        }
    }

    /**
     * 在异步线程中执行实际生成流程。
     *
     * @param jobId 生成任务主键。
     * @param options 生成参数。
     */
    private void run(Long jobId, GenerationOptions options) {
        Long projectId = lifecycleService.markRunning(jobId, GenerationStageNames.GENERATION_INPUT);
        GenerationJob job = requireJob(jobId);
        NovelProject project = requireProject(projectId);
        List<NovelChapter> chapters = chapterRepository.findByProjectIdOrderByChapterIndex(projectId);
        log.info("异步生成输入加载完成 jobId={} projectId={} chapterCount={}",
                jobId, projectId, chapters.size());

        inputSnapshotRecorder.record(job, project, chapters, options);
        lifecycleService.moveToStage(jobId, GenerationStageNames.SCRIPT_GENERATION);
        ScriptDocument document = pipeline.generate(job, project, chapters, options);

        lifecycleService.moveToStage(jobId, GenerationStageNames.SERIALIZING_JSON);
        String json = scriptJsonMapper.toJson(document);

        lifecycleService.moveToStage(jobId, GenerationStageNames.EXPORTING_YAML);
        String yaml = yamlExporter.export(document);

        lifecycleService.moveToStage(jobId, GenerationStageNames.SAVING_SNAPSHOT);
        lifecycleService.markSucceeded(jobId, document, json, yaml);
    }

    /**
     * 重新读取生成任务，确保后台执行器后续使用的是已提交到数据库的任务主键。
     *
     * @param jobId 生成任务主键。
     * @return 生成任务实体。
     */
    private GenerationJob requireJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("未找到生成任务：" + jobId));
    }

    /**
     * 重新读取小说项目，避免在异步线程中使用短事务返回的懒加载实体。
     *
     * @param projectId 项目主键。
     * @return 小说项目实体。
     */
    private NovelProject requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("未找到项目：" + projectId));
    }
}
