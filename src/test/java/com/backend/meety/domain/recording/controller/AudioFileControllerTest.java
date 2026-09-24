package com.backend.meety.domain.recording.controller;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.recording.dto.AudioFileDetailResponse;
import com.backend.meety.domain.recording.dto.AudioFileResponse;
import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.entity.AudioFilePolicy;
import com.backend.meety.domain.recording.entity.AudioFileStatus;
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
    @DisplayName("업로드 완료는 200과 AVAILABLE 상태를 반환한다")
    void completeUploadReturnsOk() throws Exception {
        when(service.completeUpload(1L, 800L, 135_000_000L, 2_700_000L)).thenReturn(new AudioFileResponse(
                800L, AudioFileStatus.AVAILABLE, "audio/mp4", 135_000_000L, 2_700_000L,
                NOW, NOW.plus(AudioFilePolicy.RETENTION)));

        mvc.perform(patch("/api/v1/audio-files/800")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileSizeBytes\":135000000,\"durationMs\":2700000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.audioFileId").value(800))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.fileSizeBytes").value(135000000))
                .andExpect(jsonPath("$.data.storedAt").exists())
                .andExpect(jsonPath("$.data.expiresAt").exists());

        verify(service).completeUpload(1L, 800L, 135_000_000L, 2_700_000L);
    }

    @Test
    @DisplayName("durationMs가 0 이하면 Service를 호출하지 않는다")
    void nonPositiveDurationIsRejected() throws Exception {
        mvc.perform(patch("/api/v1/audio-files/800")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileSizeBytes\":100,\"durationMs\":0}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("업로드되지 않은 객체면 409를 반환한다")
    void missingObjectReturnsConflict() throws Exception {
        when(service.completeUpload(1L, 800L, 100L, 100L))
                .thenThrow(new AudioFileException(AudioFileErrorCode.AUDIO_OBJECT_NOT_FOUND));

        mvc.perform(patch("/api/v1/audio-files/800")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileSizeBytes\":100,\"durationMs\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIO_OBJECT_NOT_FOUND"));
    }

    @Test
    @DisplayName("회의별 음성 파일 조회는 200과 파일 정보를 반환한다")
    void getByMeetingReturnsOk() throws Exception {
        when(service.getByMeeting(1L, 100L)).thenReturn(new AudioFileDetailResponse(
                800L, 700L, "audio/mp4", 135_000_000L, 2_700_000L,
                AudioFileStatus.AVAILABLE, NOW, NOW.plus(AudioFilePolicy.RETENTION)));

        mvc.perform(get("/api/v1/meetings/100/audio-file"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.audioFileId").value(800))
                .andExpect(jsonPath("$.data.recordingSessionId").value(700))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist());

        verify(service).getByMeeting(1L, 100L);
    }

    @Test
    @DisplayName("업로드가 끝나지 않은 파일 조회는 409를 반환한다")
    void getByMeetingNotAvailableReturnsConflict() throws Exception {
        when(service.getByMeeting(1L, 100L))
                .thenThrow(new AudioFileException(AudioFileErrorCode.AUDIO_FILE_NOT_AVAILABLE));

        mvc.perform(get("/api/v1/meetings/100/audio-file"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIO_FILE_NOT_AVAILABLE"));
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
