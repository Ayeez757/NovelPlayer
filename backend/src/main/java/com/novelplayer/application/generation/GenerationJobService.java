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
import com.novelplayer.web.dto.GenerationRequest;
import com.novelplayer.web.dto.ScriptDocumentResponse;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * 编排一次同步剧本生成任务。
 *
 * 当前实现是同步生成，但实体模型已经保存任务状态和阶段结果，
 * 后续可演进为异步任务和服务端事件进度推送。
 */
public class GenerationJobService {

    private final ProjectService projectService;
    private final NovelProjectRepository projectRepository;
    private final NovelChapterRepository chapterRepository;
    private final GenerationJobRepository jobRepository;
    private final ScriptDocumentRepository scriptDocumentRepository;
    private final ScriptGenerationPipeline pipeline;
    private final YamlExporter yamlExporter;
    private final ScriptJsonMapper scriptJsonMapper;

    public GenerationJobService(ProjectService projectService, NovelProjectRepository projectRepository,
                                NovelChapterRepository chapterRepository, GenerationJobRepository jobRepository,
                                ScriptDocumentRepository scriptDocumentRepository, ScriptGenerationPipeline pipeline,
                                YamlExporter yamlExporter, ScriptJsonMapper scriptJsonMapper) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.jobRepository = jobRepository;
        this.scriptDocumentRepository = scriptDocumentRepository;
        this.pipeline = pipeline;
        this.yamlExporter = yamlExporter;
        this.scriptJsonMapper = scriptJsonMapper;
    }

    @Transactional
    public ScriptDocumentResponse generate(Long projectId, GenerationRequest request) {
        NovelProject project = projectService.requireProject(projectId);
        List<NovelChapter> chapters = chapterRepository.findByProjectIdOrderByChapterIndex(projectId);
        GenerationOptions options = new GenerationOptions(request.format(), request.tone(),
                request.dialogueDensity(), request.narrationRetention());

        GenerationJob job = jobRepository.save(new GenerationJob(project));
        try {
            project.markGenerating();
            job.markRunning("script_generation");

            // 以 JSON 和 Java 数据对象作为权威结构，再由后端导出 YAML。
            // 这样不用让模型直接生成缩进敏感的 YAML，演示稳定性更高。
            ScriptDocument document = pipeline.generate(project, chapters, options);
            String json = scriptJsonMapper.toJson(document);
            String yaml = yamlExporter.export(document);
            ScriptDocumentEntity entity = scriptDocumentRepository.save(new ScriptDocumentEntity(
                    project, document.schemaVersion(), json, yaml, ValidationStatus.VALID));

            job.markSucceeded();
            project.markCompleted();
            projectRepository.save(project);
            jobRepository.save(job);

            return new ScriptDocumentResponse(entity.getId(), project.getId(), entity.getSchemaVersion(),
                    entity.getValidationStatus().name(), entity.getYamlContent(), entity.getCreatedAt());
        } catch (RuntimeException exception) {
            // 保存失败状态，方便前端界面展示可恢复的生成错误。
            job.markFailed(exception.getMessage());
            project.markFailed();
            projectRepository.save(project);
            jobRepository.save(job);
            throw exception;
        }
    }

    public GenerationJobResponse getJob(Long jobId) {
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("未找到生成任务：" + jobId));
        return new GenerationJobResponse(job.getId(), job.getProject().getId(), job.getStatus().name(),
                job.getCurrentStage(), job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt());
    }

    public ScriptDocumentResponse getLatestScript(Long projectId) {
        ScriptDocumentEntity entity = scriptDocumentRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new IllegalArgumentException("该项目暂无剧本文档：" + projectId));
        return new ScriptDocumentResponse(entity.getId(), projectId, entity.getSchemaVersion(),
                entity.getValidationStatus().name(), entity.getYamlContent(), entity.getCreatedAt());
    }
}
