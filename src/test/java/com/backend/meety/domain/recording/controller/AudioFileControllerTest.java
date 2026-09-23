package com.backend.meety.domain.recording.controller;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.exception.AudioFileErrorCode;
import com.backend.meety.domain.recording.exception.AudioFileException;
import com.backend.meety.domain.recording.service.AudioFileService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AudioFileControllerTest {

    private static final String UPLOAD_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/recordings/upload";

    private AudioFileService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AudioFileService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AudioFileController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("업로드 URL 발급은 201과 공통 응답 형식을 반환한다")
    void createUploadUrlReturnsCreated() throws Exception {
        when(service.createUploadUrl(1L, 700L, "audio/mp4")).thenReturn(new AudioFileUploadUrlResponse(
                800L, UPLOAD_URL, NOW.plus(AudioFilePolicy.UPLOAD_URL_VALIDITY)));

        mvc.perform(post("/api/v1/recordings/700/audio-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"audio/mp4\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.audioFileId").value(800))
                .andExpect(jsonPath("$.data.uploadUrl").value(UPLOAD_URL))
                .andExpect(jsonPath("$.data.uploadUrlExpiresAt").exists());

        verify(service).createUploadUrl(1L, 700L, "audio/mp4");
    }

    @Test
    @DisplayName("contentType이 비어 있으면 Service를 호출하지 않는다")
    void blankContentTypeIsRejected() throws Exception {
        mvc.perform(post("/api/v1/recordings/700/audio-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("이미 발급된 음성 파일이 있으면 409를 반환한다")
    void duplicateAudioFileReturnsConflict() throws Exception {
        when(service.createUploadUrl(1L, 700L, "audio/mp4"))
                .thenThrow(new AudioFileException(AudioFileErrorCode.AUDIO_FILE_ALREADY_EXISTS));

        mvc.perform(post("/api/v1/recordings/700/audio-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"audio/mp4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("AUDIO_FILE_ALREADY_EXISTS"));
    }
}
