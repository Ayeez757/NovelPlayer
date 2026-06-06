package com.novelplayer.application.generation;

import com.novelplayer.application.project.ProjectService;
import com.novelplayer.application.script.ScriptJsonMapper;
import com.novelplayer.application.script.YamlExporter;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import com.novelplayer.domain.script.ScriptDocumentEntity;
import com.novelplayer.domain.script.ValidationStatus;
import com.novelplayer.infra.repository.GenerationJobRepository;
import com.novelplayer.infra.repository.NovelChapterRepository;
import com.novelplayer.infra.repository.NovelProjectRepository;
import com.novelplayer.infra.repository.ScriptDocumentRepository;
import com.novelplayer.web.dto.GenerationJobResponse;
import com.novelplayer.web.dto.ScriptDocumentResponse;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 编排一次同步剧本生成任务。
 *
 * 当前实现是同步生成，但实体模型已经保存任务状态和阶段结果，
 * 后续可演进为异步任务和服务端事件进度推送。
 */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);

    private final ProjectService projectService;
    private final NovelProjectRepository projectRepository;
    private final NovelChapterRepository chapterRepository;
    private final GenerationJobRepository jobRepository;
    private final ScriptDocumentRepository scriptDocumentRepository;
    private final ScriptGenerationPipeline pipeline;
    private final GenerationInputSnapshotRecorder inputSnapshotRecorder;
    private final YamlExporter yamlExporter;
    private final ScriptJsonMapper scriptJsonMapper;

    /**
     * 注入生成任务需要的项目、章节、任务、文档仓储以及生成组件。
     *
     * @param projectService 项目读取服务。
     * @param projectRepository 项目仓储，用于更新项目状态。
     * @param chapterRepository 章节仓储。
     * @param jobRepository 生成任务仓储。
     * @param scriptDocumentRepository 剧本文档仓储。
     * @param pipeline 剧本生成管线。
     * @param inputSnapshotRecorder 生成输入快照记录器。
     * @param yamlExporter YAML 导出器。
     * @param scriptJsonMapper JSON 序列化器。
     */
    public GenerationJobService(ProjectService projectService, NovelProjectRepository projectRepository,
                                NovelChapterRepository chapterRepository, GenerationJobRepository jobRepository,
                                ScriptDocumentRepository scriptDocumentRepository, ScriptGenerationPipeline pipeline,
                                GenerationInputSnapshotRecorder inputSnapshotRecorder,
                                YamlExporter yamlExporter, ScriptJsonMapper scriptJsonMapper) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.jobRepository = jobRepository;
        this.scriptDocumentRepository = scriptDocumentRepository;
        this.pipeline = pipeline;
        this.inputSnapshotRecorder = inputSnapshotRecorder;
        this.yamlExporter = yamlExporter;
        this.scriptJsonMapper = scriptJsonMapper;
    }

    /**
     * 同步执行一次剧本生成，并保存任务状态、权威 JSON 和可下载 YAML。
     *
     * @param projectId 项目主键。
     * @param options 应用层生成选项。
     * @return 新生成的剧本文档响应。
     */
    @Transactional
    public ScriptDocumentResponse generate(Long projectId, GenerationOptions options) {
        log.info("Generation requested projectId={} format={} tone={} dialogueDensity={} narrationRetention={} hasAdditionalInstructions={}",
                projectId, options.format(), options.tone(), options.dialogueDensity(), options.narrationRetention(),
                options.hasAdditionalInstructions());

        NovelProject project = projectService.requireProject(projectId);
        List<NovelChapter> chapters = chapterRepository.findByProjectIdOrderByChapterIndex(projectId);
        log.info("Generation source loaded projectId={} chapterCount={}", projectId, chapters.size());

        // 每次点击生成都创建新的 Job，保留历史尝试和失败信息，便于后续做重试/审计。
        GenerationJob job = jobRepository.save(new GenerationJob(project));
        log.info("Generation job created jobId={} projectId={}", job.getId(), projectId);
        try {
            project.markGenerating();
            job.markRunning("generation_input");
            // 在真实模型调用前记录参数快照，即使后续失败也能追溯当次生成条件。
            inputSnapshotRecorder.record(job, project, chapters, options);
            job.moveToStage("staged_script_generation");
            log.info("Generation job moved to staged script generation jobId={} projectId={}", job.getId(), projectId);

            // 以 JSON 和 Java 数据对象作为权威结构，再由后端导出 YAML。
            // 这样不用让模型直接生成缩进敏感的 YAML，演示稳定性更高。
            ScriptDocument document = pipeline.generate(job, project, chapters, options);
            String json = scriptJsonMapper.toJson(document);
            String yaml = yamlExporter.export(document);
            ScriptDocumentEntity entity = scriptDocumentRepository.save(new ScriptDocumentEntity(
                    project, document.schemaVersion(), json, yaml, ValidationStatus.VALID));

            job.markSucceeded();
            project.markCompleted();
            projectRepository.save(project);
            jobRepository.save(job);
            log.info("Generation job succeeded jobId={} projectId={} scriptId={} sceneCount={} yamlLength={}",
                    job.getId(), projectId, entity.getId(), document.scenes().size(), yaml.length());

            return new ScriptDocumentResponse(entity.getId(), project.getId(), entity.getSchemaVersion(),
                    entity.getValidationStatus().name(), entity.getYamlContent(), entity.getCreatedAt());
        } catch (RuntimeException exception) {
            log.warn("Generation job failed jobId={} projectId={} stage={} error={}",
                    job.getId(), projectId, job.getCurrentStage(), exception.getMessage(), exception);
            // 保存失败状态而不是直接丢弃任务，方便前端界面展示可恢复的生成错误。
            job.markFailed(exception.getMessage());
            project.markFailed();
            projectRepository.save(project);
            jobRepository.save(job);
            throw exception;
        }
    }

    /**
     * 查询单个生成任务的当前状态，预留给异步生成进度轮询使用。
     *
     * @param jobId 生成任务主键。
     * @return 生成任务状态响应。
     */
    public GenerationJobResponse getJob(Long jobId) {
        log.debug("Loading generation job jobId={}", jobId);
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("未找到生成任务：" + jobId));
        return new GenerationJobResponse(job.getId(), job.getProject().getId(), job.getStatus().name(),
                job.getCurrentStage(), job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt());
    }

    /**
     * 读取项目最近一次成功生成的剧本文档；历史版本仍保留在数据库中。
     *
     * @param projectId 项目主键。
     * @return 最新剧本文档响应。
     */
    public ScriptDocumentResponse getLatestScript(Long projectId) {
        log.debug("Loading latest script projectId={}", projectId);
        ScriptDocumentEntity entity = scriptDocumentRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new IllegalArgumentException("该项目暂无剧本文档：" + projectId));
        return new ScriptDocumentResponse(entity.getId(), projectId, entity.getSchemaVersion(),
                entity.getValidationStatus().name(), entity.getYamlContent(), entity.getCreatedAt());
    }
}
