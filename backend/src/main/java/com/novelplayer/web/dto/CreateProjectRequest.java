package com.novelplayer.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建小说改编项目的请求体。
 *
 * @param title 作品标题。
 * @param sourceText 作者粘贴的完整小说原文。
 */
public record CreateProjectRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String sourceText
) {
}
