package com.novelplayer.application.generation;

import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocument;
import com.novelplayer.domain.script.ScriptDocumentEntity;
import com.novelplayer.domain.script.ValidationStatus;
import com.novelplayer.infra.repository.GenerationJobRepository;
import com.novelplayer.infra.repository.NovelProjectRepository;
import com.novelplayer.infra.repository.ScriptDocumentRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 生成任务生命周期持久化服务。
 *
 * <p>后台任务会跨越多个耗时阶段，生命周期状态需要用短事务及时提交，
 * 这样前端轮询任务状态时可以看到当前进度，而不是等待整个生成事务结束。</p>
 */
@Service
public class GenerationJobLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobLifecycleService.class);

    private final NovelProjectRepository projectRepository;
    private final GenerationJobRepository jobRepository;
    private final ScriptDocumentRepository scriptDocumentRepository;

    /**
     * 注入任务、项目和剧本文档仓储。
     *
     * @param projectRepository 项目仓储。
     * @param jobRepository 生成任务仓储。
     * @param scriptDocumentRepository 剧本文档仓储。
     */
    public GenerationJobLifecycleService(NovelProjectRepository projectRepository,
                                         GenerationJobRepository jobRepository,
                                         ScriptDocumentRepository scriptDocumentRepository) {
        this.projectRepository = projectRepository;
        this.jobRepository = jobRepository;
        this.scriptDocumentRepository = scriptDocumentRepository;
    }

    /**
     * 将生成任务标记为运行中，并同步标记项目为生成中。
     *
     * @param jobId 生成任务主键。
     * @param stage 当前阶段。
     * @return 生成任务所属项目主键，供异步执行器重新加载项目实体。
     */
    @Transactional
    public Long markRunning(Long jobId, String stage) {
        GenerationJob job = requireJob(jobId);
        NovelProject project = job.getProject();
        Long projectId = project.getId();
        project.markGenerating();
        job.markRunning(stage);
        projectRepository.save(project);
        jobRepository.save(job);
        log.info("异步生成任务进入运行中 jobId={} projectId={} stage={}", jobId, projectId, stage);
        return projectId;
    }

    /**
     * 更新生成任务当前阶段。
     *
     * @param jobId 生成任务主键。
     * @param stage 当前阶段。
     */
    @Transactional
    public void moveToStage(Long jobId, String stage) {
        GenerationJob job = requireJob(jobId);
        job.moveToStage(stage);
        jobRepository.save(job);
        log.info("异步生成任务阶段更新 jobId={} projectId={} stage={}", jobId, job.getProject().getId(), stage);
    }

    /**
     * 保存最终剧本文档，并将任务和项目标记为成功。
     *
     * @param jobId 生成任务主键。
     * @param document 最终剧本文档。
     * @param documentJson 剧本文档 JSON。
     * @param yamlContent 剧本文档 YAML。
     * @return 已保存的剧本文档实体。
     */
    @Transactional
    public ScriptDocumentEntity markSucceeded(Long jobId, ScriptDocument document, String documentJson,
                                              String yamlContent) {
        GenerationJob job = requireJob(jobId);
        NovelProject managedProject = job.getProject();
        ScriptDocumentEntity entity = scriptDocumentRepository.save(new ScriptDocumentEntity(
                managedProject,
                document.schemaVersion(),
                documentJson,
                yamlContent,
                ValidationStatus.VALID
        ));
        job.markSucceeded();
        managedProject.markCompleted();
        projectRepository.save(managedProject);
        jobRepository.save(job);
        log.info("异步生成任务完成 jobId={} projectId={} scriptId={} sceneCount={} yamlLength={}",
                jobId, managedProject.getId(), entity.getId(), document.scenes().size(), yamlContent.length());
        return entity;
    }

    /**
     * 将任务和项目标记为失败。
     *
     * @param jobId 生成任务主键。
     * @param message 失败原因。
     */
    @Transactional
    public void markFailed(Long jobId, String message) {
        GenerationJob job = requireJob(jobId);
        NovelProject project = job.getProject();
        job.markFailed(message);
        project.markFailed();
        projectRepository.save(project);
        jobRepository.save(job);
        log.warn("异步生成任务失败 jobId={} projectId={} stage={} error={}",
                jobId, project.getId(), job.getCurrentStage(), message);
    }

    /**
     * 读取生成任务，不存在时抛出统一错误。
     *
     * @param jobId 生成任务主键。
     * @return 生成任务实体。
     */
    private GenerationJob requireJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("未找到生成任务：" + jobId));
    }
}
