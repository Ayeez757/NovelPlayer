package com.novelplayer.web;

import com.novelplayer.application.generation.GenerationJobService;
import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.web.dto.GenerationJobResponse;
import com.novelplayer.web.dto.GenerationRequest;
import com.novelplayer.web.dto.ScriptDocumentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 剧本生成、查询和下载相关接口。
 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationJobService generationJobService;

    /**
     * 注入生成任务服务。
     *
     * @param generationJobService 负责编排生成、查询任务和读取最新剧本。
     */
    public GenerationController(GenerationJobService generationJobService) {
        this.generationJobService = generationJobService;
    }

    /**
     * 为指定项目启动一次剧本生成。
     *
     * @param projectId 项目主键。
     * @param request 生成选项。
     * @return 新创建的生成任务。
     */
    @PostMapping("/projects/{projectId}/generation-jobs")
    public ResponseEntity<GenerationJobResponse> createGenerationJob(@PathVariable Long projectId,
                                                                     @Valid @RequestBody GenerationRequest request) {
        // Controller 负责 DTO 到应用层对象的转换，避免应用服务依赖 Web 入参结构。
        GenerationOptions options = new GenerationOptions(request.format(), request.tone(),
                request.dialogueDensity(), request.narrationRetention(), request.additionalInstructions());
        return ResponseEntity.accepted().body(generationJobService.createJob(projectId, options));
    }

    /**
     * 查询生成任务状态。
     *
     * @param jobId 生成任务主键。
     * @return 任务状态、当前阶段和错误信息。
     */
    @GetMapping("/generation-jobs/{jobId}")
    public GenerationJobResponse getJob(@PathVariable Long jobId) {
        return generationJobService.getJob(jobId);
    }

    /**
     * 查询生成任务状态的旧路径兼容入口。
     *
     * @param jobId 生成任务主键。
     * @return 任务状态、当前阶段和错误信息。
     */
    @GetMapping("/jobs/{jobId}")
    public GenerationJobResponse getJobByLegacyPath(@PathVariable Long jobId) {
        return generationJobService.getJob(jobId);
    }

    /**
     * 获取项目最近一次生成的剧本文档。
     *
     * @param projectId 项目主键。
     * @return 最新剧本文档响应。
     */
    @GetMapping("/projects/{projectId}/scripts/latest")
    public ScriptDocumentResponse getLatestScript(@PathVariable Long projectId) {
        return generationJobService.getLatestScript(projectId);
    }

    /**
     * 下载项目最近一次生成的 YAML 剧本。
     *
     * @param projectId 项目主键。
     * @return YAML 文件字节响应。
     */
    @GetMapping("/projects/{projectId}/scripts/latest/download")
    public ResponseEntity<byte[]> downloadLatestScript(@PathVariable Long projectId) {
        ScriptDocumentResponse response = generationJobService.getLatestScript(projectId);
        byte[] body = response.yamlContent().getBytes(StandardCharsets.UTF_8);
        // 直接返回 YAML 字节流，浏览器会按附件下载。
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-yaml; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"script-" + projectId + ".yaml\"")
                .body(body);
    }
}
