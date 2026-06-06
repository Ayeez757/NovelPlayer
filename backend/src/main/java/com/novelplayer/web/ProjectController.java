package com.novelplayer.web;

import com.novelplayer.application.project.ProjectService;
import com.novelplayer.web.dto.CreateProjectRequest;
import com.novelplayer.web.dto.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目相关接口。
 *
 * 前端先通过这里提交小说原文并获得章节识别结果，然后再触发生成流程。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 注入项目应用服务。
     *
     * @param projectService 项目创建和查询服务。
     */
    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 创建小说改编项目，并返回章节拆分摘要。
     *
     * @param request 前端提交的作品标题和小说原文。
     * @return 项目状态与章节识别结果。
     */
    @PostMapping
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(request);
    }

    /**
     * 查询指定项目详情。
     *
     * @param projectId 项目主键。
     * @return 项目状态与章节摘要。
     */
    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable Long projectId) {
        return projectService.get(projectId);
    }
}
