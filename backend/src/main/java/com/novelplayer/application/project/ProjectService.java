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
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * 负责小说项目的创建和读取。
 *
 * 创建项目时同步完成章节拆分，确保后续模型生成始终基于已持久化、
 * 已校验的章节记录。
 */
public class ProjectService {

    private final NovelProjectRepository projectRepository;
    private final NovelChapterRepository chapterRepository;
    private final ChapterSplitter chapterSplitter;
    private final NovelPlayerProperties properties;

    public ProjectService(NovelProjectRepository projectRepository, NovelChapterRepository chapterRepository,
                          ChapterSplitter chapterSplitter, NovelPlayerProperties properties) {
        this.projectRepository = projectRepository;
        this.chapterRepository = chapterRepository;
        this.chapterSplitter = chapterSplitter;
        this.properties = properties;
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        List<ParsedChapter> parsedChapters = chapterSplitter.split(request.sourceText());
        int minimumChapters = properties.getGeneration().getMinimumChapters();
        if (parsedChapters.size() < minimumChapters) {
            throw new IllegalArgumentException("小说文本至少需要包含 " + minimumChapters + " 个章节。");
        }

        // 在同一个事务中保存原文和规范化后的章节，避免项目与章节状态不一致。
        NovelProject project = projectRepository.save(new NovelProject(request.title(), request.sourceText()));
        List<NovelChapter> chapters = parsedChapters.stream()
                .map(chapter -> new NovelChapter(project, chapter.index(), chapter.title(), chapter.content()))
                .toList();
        chapterRepository.saveAll(chapters);

        return toResponse(project, chapters);
    }

    public ProjectResponse get(Long projectId) {
        NovelProject project = requireProject(projectId);
        return toResponse(project, chapterRepository.findByProjectIdOrderByChapterIndex(projectId));
    }

    public NovelProject requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("未找到项目：" + projectId));
    }

    private ProjectResponse toResponse(NovelProject project, List<NovelChapter> chapters) {
        List<ChapterResponse> chapterResponses = chapters.stream()
                .map(chapter -> new ChapterResponse(chapter.getChapterIndex(), chapter.getTitle(), chapter.getContent().length()))
                .toList();
        return new ProjectResponse(project.getId(), project.getTitle(), project.getStatus().name(), chapterResponses,
                project.getCreatedAt(), project.getUpdatedAt());
    }
}
