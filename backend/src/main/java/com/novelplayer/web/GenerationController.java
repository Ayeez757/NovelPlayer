package com.novelplayer.web;

import com.novelplayer.application.generation.GenerationJobService;
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

@RestController
@RequestMapping("/api")
/**
 * 剧本生成、查询和下载相关接口。
 */
public class GenerationController {

    private final GenerationJobService generationJobService;

    public GenerationController(GenerationJobService generationJobService) {
        this.generationJobService = generationJobService;
    }

    @PostMapping("/projects/{projectId}/generate")
    public ScriptDocumentResponse generate(@PathVariable Long projectId, @Valid @RequestBody GenerationRequest request) {
        return generationJobService.generate(projectId, request);
    }

    @GetMapping("/jobs/{jobId}")
    public GenerationJobResponse getJob(@PathVariable Long jobId) {
        return generationJobService.getJob(jobId);
    }

    @GetMapping("/projects/{projectId}/scripts/latest")
    public ScriptDocumentResponse getLatestScript(@PathVariable Long projectId) {
        return generationJobService.getLatestScript(projectId);
    }

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
