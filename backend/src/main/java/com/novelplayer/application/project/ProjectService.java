package com.novelplayer.application.project;

import com.novelplayer.application.chapter.ChapterSplitter;
import com.novelplayer.application.chapter.ParsedChapter;
import com.novelplayer.config.NovelPlayerProperties;
import com.novelplayer.domain.project.NovelChapter;
import com.novelplayer.domain.project.NovelProject;
import com.novelplayer.infra.repository.NovelChapterRepository;
import com.novelplayer.infra.repository.NovelProjectRepository;
import com.novelplayer.web.dto.ChapterResponse;
import com.novelplayer.web.dto.CreateProjectRequest;
import com.novelplayer.web.dto.ProjectResponse;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 负责小说项目的创建和读取。
 *
 * 创建项目时同步完成章节拆分，确保后续模型生成始终基于已持久化、
 * 已校验的章节记录。
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final NovelProjectRepository projectRepository;
    private final NovelChapterRepository chapterRepository;
    private final ChapterSplitter chapterSplitter;
    private final NovelPlayerProperties properties;

    /**
     * 注入项目创建和章节持久化所需组件。
     *
     * @param projectRepository 项目仓储。
     * @param chapterRepository 章节仓储。
     * @param chapterSplitter 章节拆分器。
     * @param properties 应用配置。
     */
    public ProjectService(NovelProjectRepository projectRepository, NovelChapterRepository chapterRepository,
                          ChapterSplitter chapterSplitter, NovelPlayerProperties properties) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.chapterSplitter = chapterSplitter;
        this.properties = properties;
    }

    /**
     * 创建项目并同步完成章节拆分和持久化。
     *
     * @param request 创建项目请求。
     * @return 项目摘要响应。
     */
    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        log.info("Creating novel project titleLength={} sourceLength={}",
                request.title() == null ? 0 : request.title().length(),
                request.sourceText() == null ? 0 : request.sourceText().length());

        List<ParsedChapter> parsedChapters = chapterSplitter.split(request.sourceText());
        // 最低章节数是业务配置，不写死在服务里，便于本地演示和部署环境调整。
        int minimumChapters = properties.getGeneration().getMinimumChapters();
        log.info("Chapter split completed chapterCount={} minimumRequired={}", parsedChapters.size(), minimumChapters);
        if (parsedChapters.size() < minimumChapters) {
            log.warn("Project creation rejected because chapterCount={} is below minimumRequired={}",
                    parsedChapters.size(), minimumChapters);
            throw new IllegalArgumentException("小说文本至少需要包含 " + minimumChapters + " 个章节。");
        }

        // 在同一个事务中保存原文和规范化后的章节，避免项目与章节状态不一致。
        NovelProject project = projectRepository.save(new NovelProject(request.title(), request.sourceText()));
        List<NovelChapter> chapters = parsedChapters.stream()
                .map(chapter -> new NovelChapter(project, chapter.index(), chapter.title(), chapter.content()))
                .toList();
        chapterRepository.saveAll(chapters);
        log.info("Project created projectId={} chapterCount={}", project.getId(), chapters.size());

        return toResponse(project, chapters);
    }

    /**
     * 返回项目摘要和章节元信息，不直接返回整篇原文，避免列表/详情接口响应过大。
     *
     * @param projectId 项目主键。
     * @return 项目摘要响应。
     */
    public ProjectResponse get(Long projectId) {
        log.debug("Loading project projectId={}", projectId);
        NovelProject project = requireProject(projectId);
        return toResponse(project, chapterRepository.findByProjectIdOrderByChapterIndex(projectId));
    }

    /**
     * 统一项目存在性校验，减少各个应用服务重复拼装错误消息。
     *
     * @param projectId 项目主键。
     * @return 已存在的项目实体。
     */
    public NovelProject requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("未找到项目：" + projectId));
    }

    /**
     * 将项目实体和章节实体组装为 Web 层响应。
     *
     * @param project 项目实体。
     * @param chapters 章节实体列表。
     * @return 项目响应 DTO。
     */
    private ProjectResponse toResponse(NovelProject project, List<NovelChapter> chapters) {
        // 前端当前只需要章节标题、顺序和正文长度；正文在生成阶段由后端内部读取。
        List<ChapterResponse> chapterResponses = chapters.stream()
                .map(chapter -> new ChapterResponse(chapter.getChapterIndex(), chapter.getTitle(), chapter.getContent().length()))
                .toList();
        return new ProjectResponse(project.getId(), project.getTitle(), project.getStatus().name(), chapterResponses,
                project.getCreatedAt(), project.getUpdatedAt());
    }
}
