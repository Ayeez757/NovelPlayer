package com.novelplayer.web;

import com.novelplayer.application.generation.GenerationJobService;
import com.novelplayer.application.generation.GenerationOptions;
import com.novelplayer.web.dto.GenerationJobResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the API path used by the frontend generate-script button.
 */
@WebMvcTest(GenerationController.class)
class GenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerationJobService generationJobService;

    @Test
    void createsGenerationJobOnFrontendApiPath() throws Exception {
        GenerationJobResponse response = new GenerationJobResponse(11L, 7L, "PENDING",
                "created", null, OffsetDateTime.parse("2026-06-07T12:00:00+08:00"), null, null);
        when(generationJobService.createJob(eq(7L), any(GenerationOptions.class))).thenReturn(response);

        mockMvc.perform(post("/api/projects/7/generation-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "web_drama",
                                  "tone": "suspense",
                                  "dialogueDensity": 60,
                                  "narrationRetention": 30,
                                  "additionalInstructions": ""
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.projectId").value(7))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(generationJobService).createJob(eq(7L), any(GenerationOptions.class));
    }
}
