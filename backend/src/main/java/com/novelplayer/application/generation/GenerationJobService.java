package com.novelplayer.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelplayer.application.generation.model.ScenePlan;
import com.novelplayer.application.project.ProjectService;
import com.novelplayer.domain.generation.GenerationJob;
import com.novelplayer.domain.generation.GenerationStageResult;
import com.novelplayer.domain.generation.GenerationStatus;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.domain.script.ScriptDocumentEntity;
import com.novelplayer.infra.repository.GenerationStageResultRepository;
import com.novelplayer.infra.repository.GenerationJobRepository;
import com.novelplayer.infra.repository.NovelChapterRepository;
import com.novelplayer.infra.repository.ScriptDocumentRepository;
import com.novelplayer.web.dto.GenerationJobResponse;
import com.novelplayer.web.dto.ScriptDocumentResponse;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 编排一次异步剧本生成任务。
 *
 * <p>服务只负责创建任务、查询任务状态和读取最新结果；耗时的生成流程交给后台执行器，
 * 避免 HTTP 请求长时间阻塞。</p>
 */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);

    private final ProjectService projectService;
    private final GenerationJobRepository jobRepository;
    private final ScriptDocumentRepository scriptDocumentRepository;
    private final NovelChapterRepository chapterRepository;
    private final GenerationStageResultRepository stageResultRepository;
    private final GenerationJobExecutor generationJobExecutor;
    private final GenerationJobLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    /**
     * 注入生成任务创建、查询和后台执行所需组件。
     *
     * @param projectService 项目读取服务。
     * @param jobRepository 生成任务仓储。
     * @param scriptDocumentRepository 剧本文档仓储。
     *                                 章节仓储
     *                                 阶段结果仓储
     * @param generationJobExecutor 后台生成任务执行器。
     * @param lifecycleService 生成任务生命周期服务。
     */
    /*
     * 旧构造器没有阶段进度统计所需的依赖：
     * public GenerationJobService(ProjectService projectService, GenerationJobRepository jobRepository,
     *                             ScriptDocumentRepository scriptDocumentRepository,
     *                             GenerationJobExecutor generationJobExecutor,
     *                             GenerationJobLifecycleService lifecycleService) { ... }
     */
    public GenerationJobService(ProjectService projectService, GenerationJobRepository jobRepository,
                                ScriptDocumentRepository scriptDocumentRepository,
                                NovelChapterRepository chapterRepository,
                                GenerationStageResultRepository stageResultRepository,
                                GenerationJobExecutor generationJobExecutor,
                                GenerationJobLifecycleService lifecycleService,
                                ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.jobRepository = jobRepository;
        this.scriptDocumentRepository = scriptDocumentRepository;
        this.chapterRepository = chapterRepository;
        this.stageResultRepository = stageResultRepository;
        this.generationJobExecutor = generationJobExecutor;
        this.lifecycleService = lifecycleService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一次异步剧本生成任务，并立即返回任务状态。
     *
     * @param projectId 项目主键。
     * @param options 应用层生成选项。
     * @return 新创建的生成任务响应。
     */
    @Transactional
    public GenerationJobResponse createJob(Long projectId, GenerationOptions options) {
        log.info("收到异步剧本生成请求 projectId={} format={} tone={} dialogueDensity={} narrationRetention={} hasAdditionalInstructions={}",
                projectId, options.format(), options.tone(), options.dialogueDensity(), options.narrationRetention(),
                options.hasAdditionalInstructions());

        NovelProject project = projectService.requireProject(projectId);
        // 每次点击生成都创建新的 Job，保留历史尝试和失败信息，便于后续做重试/审计。
        GenerationJob job = jobRepository.save(new GenerationJob(project));
        log.info("异步生成任务已创建 jobId={} projectId={}", job.getId(), projectId);
        dispatchAfterCommit(job.getId(), options);
        return toResponse(job);
    }

    /**
     * 查询单个生成任务的当前状态，预留给异步生成进度轮询使用。
     *
     * @param jobId 生成任务主键。
     * @return 生成任务状态响应。
     */
    @Transactional
    public GenerationJobResponse getJob(Long jobId) {
        log.debug("读取生成任务状态 jobId={}", jobId);
        GenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("未找到生成任务：" + jobId));
        return toResponse(job);
    }

    /**
     * 在当前事务成功提交后再调度后台生成，避免异步线程读取到尚未提交的任务记录。
     *
     * @param jobId 生成任务主键。
     * @param options 生成参数。
     */
    private void dispatchAfterCommit(Long jobId, GenerationOptions options) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.info("当前没有活动事务，立即调度异步生成任务 jobId={}", jobId);
            dispatch(jobId, options);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 创建任务事务提交后触发后台生成。
             */
            @Override
            public void afterCommit() {
                log.info("创建任务事务已提交，开始调度异步生成任务 jobId={}", jobId);
                dispatch(jobId, options);
            }
        });
        log.debug("异步生成任务已注册事务提交后调度 jobId={}", jobId);
    }

    /**
     * 提交后台生成任务；如果线程池拒绝执行，则立即把任务标记为失败。
     *
     * @param jobId 生成任务主键。
     * @param options 生成参数。
     */
    private void dispatch(Long jobId, GenerationOptions options) {
        try {
            generationJobExecutor.execute(jobId, options);
        } catch (RuntimeException exception) {
            log.warn("异步生成任务提交失败 jobId={} error={}", jobId, exception.getMessage(), exception);
            lifecycleService.markFailed(jobId, "后台生成任务提交失败：" + exception.getMessage());
        }
    }

    /**
     * 读取项目最近一次成功生成的剧本文档；历史版本仍保留在数据库中。
     *
     * @param projectId 项目主键。
     * @return 最新剧本文档响应。
     */
    public ScriptDocumentResponse getLatestScript(Long projectId) {
        log.debug("读取项目最新剧本文档 projectId={}", projectId);
        ScriptDocumentEntity entity = scriptDocumentRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> new IllegalArgumentException("该项目暂无剧本文档：" + projectId));
        return new ScriptDocumentResponse(entity.getId(), projectId, entity.getSchemaVersion(),
                entity.getValidationStatus().name(), entity.getYamlContent(), entity.getCreatedAt());
    }

    /**
     * 将生成任务实体转换为状态响应。
     *
     * @param job 生成任务实体。
     * @return 生成任务状态响应。
     */
    private GenerationJobResponse toResponse(GenerationJob job) {
        /*
         * 旧版响应只返回状态和 currentStage，没有把阶段总量/完成量带出去。
         * 现在在这里统一组装 progress，避免控制器和前端再各自猜测。
         */
        return new GenerationJobResponse(job.getId(), job.getProject().getId(), job.getStatus().name(),
                job.getCurrentStage(), job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt(),
                resolveProgress(job));
    }

    private GenerationJobResponse.Progress resolveProgress(GenerationJob job) {
        String stage = job.getCurrentStage();
        if (stage == null || job.getId() == null) {
            return null;
        }

        if (stage.equals(GenerationStageNames.CHAPTER_DIGEST)
                || stage.startsWith(GenerationStageNames.CHAPTER_DIGEST + ":")) {
            int total = Math.toIntExact(chapterRepository.countByProjectId(job.getProject().getId()));
            int completed = Math.toIntExact(stageResultRepository.countByJobIdAndStatusAndStageNameStartingWith(
                    job.getId(), GenerationStatus.SUCCEEDED, GenerationStageNames.CHAPTER_DIGEST + ":"));
            int failed = Math.toIntExact(stageResultRepository.countByJobIdAndStatusAndStageNameStartingWith(
                    job.getId(), GenerationStatus.FAILED, GenerationStageNames.CHAPTER_DIGEST + ":"));
            return new GenerationJobResponse.Progress(total, completed, failed);
        }

        if (stage.equals(GenerationStageNames.SCENE_DRAFT)
                || stage.startsWith(GenerationStageNames.SCENE_DRAFT + ":")) {
            int total = resolveSceneDraftTotal(job.getId());
            if (total <= 0) {
                return null;
            }
            int completed = Math.toIntExact(stageResultRepository.countByJobIdAndStatusAndStageNameStartingWith(
                    job.getId(), GenerationStatus.SUCCEEDED, GenerationStageNames.SCENE_DRAFT + ":"));
            int failed = Math.toIntExact(stageResultRepository.countByJobIdAndStatusAndStageNameStartingWith(
                    job.getId(), GenerationStatus.FAILED, GenerationStageNames.SCENE_DRAFT + ":"));
            return new GenerationJobResponse.Progress(total, completed, failed);
        }

        return null;
    }

    private int resolveSceneDraftTotal(Long jobId) {
        return stageResultRepository
                .findFirstByJobIdAndStageNameAndStatusOrderByCreatedAtDesc(
                        jobId, GenerationStageNames.SCENE_PLAN, GenerationStatus.SUCCEEDED)
                .map(GenerationStageResult::getOutputJson)
                .map(this::readScenePlanSize)
                .orElse(0);
    }

    private int readScenePlanSize(String outputJson) {
        try {
            // 这里只为了进度显示读取 scene 数量，解析失败时保守返回 0，不影响主流程。
            return objectMapper.readValue(outputJson, ScenePlan.class).scenes().size();
        } catch (Exception exception) {
            log.warn("Failed to parse scene_plan when resolving generation progress", exception);
            return 0;
        }
    }
}
